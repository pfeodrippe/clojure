(ns clojure.compiler.phase6
  "Phase 6: Bytecode Generation
  
  This phase provides Clojure functions for generating JVM bytecode using the ASM library.
  It wraps ASM's visitor-based API in functional interfaces for class and method emission.
  
  Key capabilities:
  - Class creation and finalization
  - Method signature generation
  - Bytecode instruction emission
  - Stack depth tracking
  - Type descriptor generation
  - Local variable management
  
  Usage:
    (def class-bytes
      (-> (create-class-builder \"MyClass\" \"java/lang/Object\")
          (add-method {:name \"add\" :params [int int] :return int}
                      (fn [ctx]
                        (-> ctx
                            (emit-load-arg 0)
                            (emit-load-arg 1)
                            (emit-iadd)
                            (emit-return))))
          (finalize-class)))
    
    ;; Load the generated class
    (define-class (class-loader) \"MyClass\" class-bytes)
  "
  (:require [clojure.string :as str])
  (:import [clojure.asm ClassWriter Opcodes Type]))

;;; ============================================================================
;;; Type Descriptor Generation
;;; ============================================================================

(defn java-type->descriptor
  "Convert a Java type to its bytecode descriptor.
  
  Examples:
    int -> \"I\"
    long -> \"J\"
    String -> \"Ljava/lang/String;\"
    int[] -> \"[I\"
    Object[] -> \"[Ljava/lang/Object;\"
  "
  [type]
  (cond
    (= type 'void) "V"
    (= type 'boolean) "Z"
    (= type 'byte) "B"
    (= type 'char) "C"
    (= type 'short) "S"
    (= type 'int) "I"
    (= type 'long) "J"
    (= type 'float) "F"
    (= type 'double) "D"
    ;; Handle array types
    (= type 'int-array) "[I"
    (= type 'long-array) "[J"
    (= type 'float-array) "[F"
    (= type 'double-array) "[D"
    (= type 'byte-array) "[B"
    (= type 'char-array) "[C"
    (= type 'short-array) "[S"
    (= type 'boolean-array) "[Z"
    (symbol? type) (str "L" (str/replace (name type) #"\." "/") ";")
    (class? type) (Type/getDescriptor type)
    (string? type) (if (.startsWith type "L")
                     type  ; Already a descriptor
                     (str "L" (str/replace type #"\." "/") ";"))
    :else (str "L" (str/replace (str type) #"\." "/") ";")))

(defn method-descriptor
  "Generate a method descriptor from parameter types and return type.
  
  Example:
    (method-descriptor [int String] void)
    => \"(ILjava/lang/String;)V\"
  "
  [param-types return-type]
  (str "("
       (apply str (map java-type->descriptor param-types))
       ")"
       (java-type->descriptor return-type)))

(defn type-size
  "Return the stack size of a type (1 for most, 2 for long/double)."
  [type]
  (if (or (= type 'long) (= type 'double))
    2
    1))

;;; ============================================================================
;;; Class Creation
;;; ============================================================================

(defn create-class-builder
  "Create a new class builder for bytecode generation.
  
  Returns a map containing:
    :class-writer - The ASM ClassWriter
    :class-name   - The internal class name
    :methods      - Vector of method definitions
  
  Options:
    :super-class  - Superclass name (default: java/lang/Object)
    :interfaces   - Vector of interface names
    :access       - Access flags (default: ACC_PUBLIC)
  "
  ([class-name]
   (create-class-builder class-name {}))
  ([class-name {:keys [super-class interfaces access]
                :or {super-class "java/lang/Object"
                     interfaces []
                     access Opcodes/ACC_PUBLIC}}]
   (let [cw (ClassWriter. (bit-or ClassWriter/COMPUTE_MAXS
                                  ClassWriter/COMPUTE_FRAMES))
         internal-name (str/replace class-name #"\." "/")]
     (.visit cw Opcodes/V1_8 access internal-name nil super-class
             (into-array String interfaces))
     {:class-writer cw
      :class-name internal-name
      :super-class super-class
      :interfaces interfaces
      :methods []})))

(defn finalize-class
  "Finalize the class and return the bytecode as a byte array."
  [{:keys [class-writer]}]
  (.visitEnd class-writer)
  (.toByteArray class-writer))

;;; ============================================================================
;;; Method Creation
;;; ============================================================================

(defn add-method
  "Add a method to the class builder.
  
  Method spec:
    {:name \"methodName\"
     :params [int String]  ; Parameter types
     :return void          ; Return type
     :access ACC_PUBLIC    ; Optional, default ACC_PUBLIC
     :static? true}        ; Optional, default false
  
  Body-fn receives a context map and should return the updated context.
  The context contains:
    :method-visitor - The ASM MethodVisitor
    :stack-depth    - Current stack depth
    :max-stack      - Maximum stack depth seen
    :local-vars     - Map of local variable indices
  "
  [builder method-spec body-fn]
  (let [{:keys [class-writer class-name]} builder
        {:keys [name params return access static?]
         :or {access Opcodes/ACC_PUBLIC
              static? false}} method-spec
        descriptor (method-descriptor params return)
        access-flags (if static?
                      (bit-or access Opcodes/ACC_STATIC)
                      access)
        mv (.visitMethod class-writer access-flags name descriptor nil nil)
        
        ;; Calculate starting local index based on params
        ;; Non-static: slot 0 is 'this', then params
        ;; Static: params start at slot 0
        ;; Longs and doubles take 2 slots each
        param-slots (reduce + (map type-size params))
        starting-local (if static? 0 1)
        next-local (+ starting-local param-slots)
        
        ;; Initialize context for method body
        initial-ctx {:method-visitor mv
                     :class-name class-name
                     :method-name name
                     :stack-depth 0
                     :max-stack 0
                     :local-index next-local  ; Next available local variable slot
                     :params params
                     :return return
                     :static? static?}]
    
    (.visitCode mv)
    
    ;; Execute the method body function
    (let [final-ctx (body-fn initial-ctx)]
      ;; Note: Method body is responsible for calling emit-return
      (.visitMaxs mv (:max-stack final-ctx 0) (:local-index final-ctx 0))
      (.visitEnd mv))
    
    (update builder :methods conj method-spec)))

;;; ============================================================================
;;; Stack Management
;;; ============================================================================

(defn- update-stack
  "Update stack depth and track maximum."
  [ctx delta]
  (let [new-depth (+ (:stack-depth ctx) delta)
        new-max (max (:max-stack ctx) new-depth)]
    (assoc ctx
           :stack-depth new-depth
           :max-stack new-max)))

(defn push-stack
  "Increase stack depth (for values being pushed)."
  ([ctx] (push-stack ctx 1))
  ([ctx n] (update-stack ctx n)))

(defn pop-stack
  "Decrease stack depth (for values being consumed)."
  ([ctx] (pop-stack ctx 1))
  ([ctx n] (update-stack ctx (- n))))

;;; ============================================================================
;;; Load/Store Instructions
;;; ============================================================================

(defn emit-load-arg
  "Load a method argument onto the stack.
  For static methods, arg 0 is the first parameter.
  For instance methods, arg 0 is still the first parameter (not 'this')."
  [ctx arg-index]
  (let [{:keys [method-visitor params static?]} ctx
        ;; Calculate actual slot index accounting for:
        ;; 1. 'this' pointer for non-static methods (slot 0)
        ;; 2. Previous parameters that take 2 slots (long/double)
        param-type (get params arg-index 'java.lang.Object)
        previous-params (take arg-index params)
        previous-slots (reduce + (map type-size previous-params))
        base-slot (if static? 0 1)  ; 'this' takes slot 0 for non-static
        actual-index (+ base-slot previous-slots)]
    (cond
      (or (= param-type 'int)
          (= param-type 'boolean)
          (= param-type 'byte)
          (= param-type 'char)
          (= param-type 'short))
      (.visitVarInsn method-visitor Opcodes/ILOAD actual-index)
      
      (= param-type 'long)
      (.visitVarInsn method-visitor Opcodes/LLOAD actual-index)
      
      (= param-type 'float)
      (.visitVarInsn method-visitor Opcodes/FLOAD actual-index)
      
      (= param-type 'double)
      (.visitVarInsn method-visitor Opcodes/DLOAD actual-index)
      
      :else
      (.visitVarInsn method-visitor Opcodes/ALOAD actual-index))
    
    (push-stack ctx (type-size param-type))))

(defn emit-store-local
  "Store top of stack to a local variable."
  [ctx var-index type]
  (let [{:keys [method-visitor]} ctx]
    (cond
      (or (= type 'int)
          (= type 'boolean)
          (= type 'byte)
          (= type 'char)
          (= type 'short))
      (.visitVarInsn method-visitor Opcodes/ISTORE var-index)
      
      (= type 'long)
      (.visitVarInsn method-visitor Opcodes/LSTORE var-index)
      
      (= type 'float)
      (.visitVarInsn method-visitor Opcodes/FSTORE var-index)
      
      (= type 'double)
      (.visitVarInsn method-visitor Opcodes/DSTORE var-index)
      
      :else
      (.visitVarInsn method-visitor Opcodes/ASTORE var-index))
    
    (pop-stack ctx (type-size type))))

(defn emit-load-local
  "Load a local variable onto the stack."
  [ctx var-index type]
  (let [{:keys [method-visitor]} ctx]
    (cond
      (or (= type 'int)
          (= type 'boolean)
          (= type 'byte)
          (= type 'char)
          (= type 'short))
      (.visitVarInsn method-visitor Opcodes/ILOAD var-index)
      
      (= type 'long)
      (.visitVarInsn method-visitor Opcodes/LLOAD var-index)
      
      (= type 'float)
      (.visitVarInsn method-visitor Opcodes/FLOAD var-index)
      
      (= type 'double)
      (.visitVarInsn method-visitor Opcodes/DLOAD var-index)
      
      :else
      (.visitVarInsn method-visitor Opcodes/ALOAD var-index))
    
    (push-stack ctx (type-size type))))

;;; ============================================================================
;;; Constant Loading
;;; ============================================================================

(defn emit-const
  "Load a constant value onto the stack."
  [ctx value]
  (let [{:keys [method-visitor]} ctx]
    (cond
      (nil? value)
      (.visitInsn method-visitor Opcodes/ACONST_NULL)
      
      (boolean? value)
      (.visitInsn method-visitor (if value Opcodes/ICONST_1 Opcodes/ICONST_0))
      
      (and (integer? value) (>= value -1) (<= value 5))
      (.visitInsn method-visitor (+ Opcodes/ICONST_0 value))
      
      (and (integer? value) (>= value Byte/MIN_VALUE) (<= value Byte/MAX_VALUE))
      (.visitIntInsn method-visitor Opcodes/BIPUSH value)
      
      (and (integer? value) (>= value Short/MIN_VALUE) (<= value Short/MAX_VALUE))
      (.visitIntInsn method-visitor Opcodes/SIPUSH value)
      
      (integer? value)
      (.visitLdcInsn method-visitor (int value))
      
      (float? value)
      (.visitLdcInsn method-visitor (float value))
      
      (instance? Long value)
      (.visitLdcInsn method-visitor (long value))
      
      (instance? Double value)
      (.visitLdcInsn method-visitor (double value))
      
      (string? value)
      (.visitLdcInsn method-visitor value)
      
      :else
      (.visitLdcInsn method-visitor value))
    
    (push-stack ctx 1)))

;;; ============================================================================
;;; Arithmetic Instructions
;;; ============================================================================

(defn emit-iadd
  "Add two integers (pops 2, pushes 1)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/IADD)
  (-> ctx (pop-stack 2) (push-stack 1)))

(defn emit-isub
  "Subtract two integers (pops 2, pushes 1)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/ISUB)
  (-> ctx (pop-stack 2) (push-stack 1)))

(defn emit-imul
  "Multiply two integers (pops 2, pushes 1)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/IMUL)
  (-> ctx (pop-stack 2) (push-stack 1)))

(defn emit-idiv
  "Divide two integers (pops 2, pushes 1)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/IDIV)
  (-> ctx (pop-stack 2) (push-stack 1)))

(defn emit-ladd
  "Add two longs (pops 4, pushes 2)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/LADD)
  (-> ctx (pop-stack 4) (push-stack 2)))

(defn emit-lsub
  "Subtract two longs (pops 4, pushes 2)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/LSUB)
  (-> ctx (pop-stack 4) (push-stack 2)))

(defn emit-lmul
  "Multiply two longs (pops 4, pushes 2)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/LMUL)
  (-> ctx (pop-stack 4) (push-stack 2)))

;;; ============================================================================
;;; Method Invocation
;;; ============================================================================

(defn emit-invoke-static
  "Invoke a static method."
  [ctx class-name method-name param-types return-type]
  (let [{:keys [method-visitor]} ctx
        descriptor (method-descriptor param-types return-type)
        internal-name (str/replace class-name #"\." "/")
        param-stack (reduce + (map type-size param-types))]
    (.visitMethodInsn method-visitor
                      Opcodes/INVOKESTATIC
                      internal-name
                      method-name
                      descriptor
                      false)
    (-> ctx
        (pop-stack param-stack)
        (push-stack (if (= return-type 'void) 0 (type-size return-type))))))

(defn emit-invoke-virtual
  "Invoke a virtual (instance) method."
  [ctx class-name method-name param-types return-type]
  (let [{:keys [method-visitor]} ctx
        descriptor (method-descriptor param-types return-type)
        internal-name (str/replace class-name #"\." "/")
        ;; Pop 'this' + parameters
        param-stack (inc (reduce + (map type-size param-types)))]
    (.visitMethodInsn method-visitor
                      Opcodes/INVOKEVIRTUAL
                      internal-name
                      method-name
                      descriptor
                      false)
    (-> ctx
        (pop-stack param-stack)
        (push-stack (if (= return-type 'void) 0 (type-size return-type))))))

(defn emit-invoke-special
  "Invoke a special method (constructor, private, super)."
  [ctx class-name method-name param-types return-type]
  (let [{:keys [method-visitor]} ctx
        descriptor (method-descriptor param-types return-type)
        internal-name (str/replace class-name #"\." "/")
        param-stack (inc (reduce + (map type-size param-types)))]
    (.visitMethodInsn method-visitor
                      Opcodes/INVOKESPECIAL
                      internal-name
                      method-name
                      descriptor
                      false)
    (-> ctx
        (pop-stack param-stack)
        (push-stack (if (= return-type 'void) 0 (type-size return-type))))))

;;; ============================================================================
;;; Return Instructions
;;; ============================================================================

(defn emit-return
  "Emit return instruction based on method's return type."
  [ctx]
  (let [{:keys [method-visitor return]} ctx]
    (cond
      (= return 'void)
      (.visitInsn method-visitor Opcodes/RETURN)
      
      (or (= return 'int)
          (= return 'boolean)
          (= return 'byte)
          (= return 'char)
          (= return 'short))
      (.visitInsn method-visitor Opcodes/IRETURN)
      
      (= return 'long)
      (.visitInsn method-visitor Opcodes/LRETURN)
      
      (= return 'float)
      (.visitInsn method-visitor Opcodes/FRETURN)
      
      (= return 'double)
      (.visitInsn method-visitor Opcodes/DRETURN)
      
      :else
      (.visitInsn method-visitor Opcodes/ARETURN))
    
    (assoc ctx :stack-depth 0)))

;;; ============================================================================
;;; Field Access
;;; ============================================================================

(defn emit-get-field
  "Get an instance field value."
  [ctx class-name field-name field-type]
  (let [{:keys [method-visitor]} ctx
        internal-name (str/replace class-name #"\." "/")
        descriptor (java-type->descriptor field-type)]
    (.visitFieldInsn method-visitor
                     Opcodes/GETFIELD
                     internal-name
                     field-name
                     descriptor)
    (-> ctx
        (pop-stack 1)  ; Pop object reference
        (push-stack (type-size field-type)))))

(defn emit-put-field
  "Set an instance field value."
  [ctx class-name field-name field-type]
  (let [{:keys [method-visitor]} ctx
        internal-name (str/replace class-name #"\." "/")
        descriptor (java-type->descriptor field-type)]
    (.visitFieldInsn method-visitor
                     Opcodes/PUTFIELD
                     internal-name
                     field-name
                     descriptor)
    (pop-stack ctx (inc (type-size field-type)))))  ; Pop value + object

(defn emit-get-static
  "Get a static field value."
  [ctx class-name field-name field-type]
  (let [{:keys [method-visitor]} ctx
        internal-name (str/replace class-name #"\." "/")
        descriptor (java-type->descriptor field-type)]
    (.visitFieldInsn method-visitor
                     Opcodes/GETSTATIC
                     internal-name
                     field-name
                     descriptor)
    (push-stack ctx (type-size field-type))))

(defn emit-put-static
  "Set a static field value."
  [ctx class-name field-name field-type]
  (let [{:keys [method-visitor]} ctx
        internal-name (str/replace class-name #"\." "/")
        descriptor (java-type->descriptor field-type)]
    (.visitFieldInsn method-visitor
                     Opcodes/PUTSTATIC
                     internal-name
                     field-name
                     descriptor)
    (pop-stack ctx (type-size field-type))))

;;; ============================================================================
;;; Class Loading
;;; ============================================================================

(defn define-class
  "Define a class from bytecode using Clojure's DynamicClassLoader.
  Returns the defined Class object."
  [class-name bytecode]
  (let [loader (clojure.lang.RT/makeClassLoader)]
    (.defineClass loader class-name bytecode nil)))

;;; ============================================================================
;;; Utility Functions
;;; ============================================================================

(defn add-default-constructor
  "Add a default no-arg constructor that calls super()."
  [builder]
  (add-method builder
              {:name "<init>"
               :params []
               :return 'void
               :access Opcodes/ACC_PUBLIC}
              (fn [ctx]
                (-> ctx
                    ;; Load 'this'
                    (emit-load-arg -1)  ; Special case: -1 means 'this'
                    ;; Call super constructor
                    (emit-invoke-special (:super-class builder) "<init>" [] 'void)
                    (emit-return)))))

(defn class-loader
  "Get the current thread's context class loader."
  []
  (.getContextClassLoader (Thread/currentThread)))

(comment
  ;; Example: Generate a simple class with an add method
  (def my-class-bytes
    (-> (create-class-builder "MyMathClass")
        (add-method {:name "add"
                     :params ['int 'int]
                     :return 'int
                     :static? true}
                    (fn [ctx]
                      (-> ctx
                          (emit-load-arg 0)
                          (emit-load-arg 1)
                          (emit-iadd)
                          (emit-return))))
        (finalize-class)))
  
  ;; Load and use the class
  (def my-class (define-class "MyMathClass" my-class-bytes))
  (def add-method (.getMethod my-class "add" (into-array Class [Integer/TYPE Integer/TYPE])))
  (.invoke add-method nil (object-array [10 32]))
  ;; => 42
  )

;;; ============================================================================
;;; Phase 7: Control Flow, Objects, and Arrays
;;; ============================================================================

;;; --- Labels and Jumps (Control Flow) ---

(defn create-label
  "Create a new Label for jump targets."
  []
  (clojure.asm.Label.))

(defn mark-label
  "Mark a label at the current position in bytecode."
  [ctx label]
  (let [{:keys [method-visitor]} ctx]
    (.visitLabel method-visitor label)
    ctx))

(defn emit-goto
  "Unconditional jump to a label."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/GOTO label)
  ctx)

(defn emit-if-eq
  "Jump to label if top two ints on stack are equal (pops 2)."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/IF_ICMPEQ label)
  (pop-stack ctx 2))

(defn emit-if-ne
  "Jump to label if top two ints on stack are not equal (pops 2)."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/IF_ICMPNE label)
  (pop-stack ctx 2))

(defn emit-if-lt
  "Jump to label if second < top (int comparison, pops 2)."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/IF_ICMPLT label)
  (pop-stack ctx 2))

(defn emit-if-le
  "Jump to label if second <= top (int comparison, pops 2)."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/IF_ICMPLE label)
  (pop-stack ctx 2))

(defn emit-if-gt
  "Jump to label if second > top (int comparison, pops 2)."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/IF_ICMPGT label)
  (pop-stack ctx 2))

(defn emit-if-ge
  "Jump to label if second >= top (int comparison, pops 2)."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/IF_ICMPGE label)
  (pop-stack ctx 2))

(defn emit-ifnull
  "Jump to label if top of stack is null (pops 1)."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/IFNULL label)
  (pop-stack ctx 1))

(defn emit-ifnonnull
  "Jump to label if top of stack is not null (pops 1)."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/IFNONNULL label)
  (pop-stack ctx 1))

(defn emit-ifeq
  "Jump to label if top int == 0 (pops 1)."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/IFEQ label)
  (pop-stack ctx 1))

(defn emit-ifne
  "Jump to label if top int != 0 (pops 1)."
  [ctx label]
  (.visitJumpInsn (:method-visitor ctx) Opcodes/IFNE label)
  (pop-stack ctx 1))

;;; --- Object Creation ---

(defn emit-new
  "Create a new object instance (pushes reference, not yet initialized).
  class-name should be internal name like 'java/lang/String'."
  [ctx class-name]
  (let [{:keys [method-visitor]} ctx
        internal-name (str/replace class-name #"\." "/")]
    (.visitTypeInsn method-visitor Opcodes/NEW internal-name)
    (push-stack ctx 1)))

(defn emit-dup
  "Duplicate top of stack (pushes copy of top value)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/DUP)
  (push-stack ctx 1))

(defn emit-new-object
  "Complete object creation pattern: NEW + DUP + args + INVOKESPECIAL <init>.
  
  Example:
    (emit-new-object ctx \"java/lang/String\" [String] 
                     (fn [c] (emit-const c \"hello\")))
    ;; Equivalent to: new String(\"hello\")
  "
  [ctx class-name constructor-param-types args-fn]
  (let [internal-name (str/replace class-name #"\." "/")]
    (-> ctx
        (emit-new internal-name)
        (emit-dup)
        (args-fn)  ; Push constructor arguments
        (emit-invoke-special internal-name "<init>" constructor-param-types 'void))))

;;; --- Array Operations ---

(defn emit-newarray
  "Create a new primitive array. 
  type should be one of: boolean, char, float, double, byte, short, int, long
  Size should be on stack (int)."
  [ctx array-type]
  (let [{:keys [method-visitor]} ctx
        type-code ({:boolean Opcodes/T_BOOLEAN
                    :char Opcodes/T_CHAR
                    :float Opcodes/T_FLOAT
                    :double Opcodes/T_DOUBLE
                    :byte Opcodes/T_BYTE
                    :short Opcodes/T_SHORT
                    :int Opcodes/T_INT
                    :long Opcodes/T_LONG
                    'boolean Opcodes/T_BOOLEAN
                    'char Opcodes/T_CHAR
                    'float Opcodes/T_FLOAT
                    'double Opcodes/T_DOUBLE
                    'byte Opcodes/T_BYTE
                    'short Opcodes/T_SHORT
                    'int Opcodes/T_INT
                    'long Opcodes/T_LONG} array-type)]
    (.visitIntInsn method-visitor Opcodes/NEWARRAY type-code)
    ctx))  ; Stack: size -> arrayref (same depth)

(defn emit-anewarray
  "Create a new object array.
  element-type-name should be internal name like 'java/lang/String'
  Size should be on stack (int)."
  [ctx element-type-name]
  (let [{:keys [method-visitor]} ctx
        internal-name (str/replace element-type-name #"\." "/")]
    (.visitTypeInsn method-visitor Opcodes/ANEWARRAY internal-name)
    ctx))  ; Stack: size -> arrayref (same depth)

(defn emit-iaload
  "Load int from array (pops arrayref + index, pushes value)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/IALOAD)
  (-> ctx (pop-stack 2) (push-stack 1)))

(defn emit-iastore
  "Store int to array (pops arrayref + index + value)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/IASTORE)
  (pop-stack ctx 3))

(defn emit-aaload
  "Load object from array (pops arrayref + index, pushes value)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/AALOAD)
  (-> ctx (pop-stack 2) (push-stack 1)))

(defn emit-aastore
  "Store object to array (pops arrayref + index + value)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/AASTORE)
  (pop-stack ctx 3))

(defn emit-arraylength
  "Get array length (pops arrayref, pushes int length)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/ARRAYLENGTH)
  ctx)  ; Pop ref, push int = same depth

;;; --- Type Casting and Checking ---

(defn emit-checkcast
  "Cast object to specified type (pops objectref, pushes casted ref).
  type-name should be internal name like 'java/lang/String'."
  [ctx type-name]
  (let [{:keys [method-visitor]} ctx
        internal-name (str/replace type-name #"\." "/")]
    (.visitTypeInsn method-visitor Opcodes/CHECKCAST internal-name)
    ctx))  ; Stack depth unchanged

(defn emit-instanceof
  "Check if object is instance of type (pops objectref, pushes int 0/1).
  type-name should be internal name like 'java/lang/String'."
  [ctx type-name]
  (let [{:keys [method-visitor]} ctx
        internal-name (str/replace type-name #"\." "/")]
    (.visitTypeInsn method-visitor Opcodes/INSTANCEOF internal-name)
    ctx))  ; Pop ref, push int = same depth

;;; --- Type Conversions ---

(defn emit-i2l
  "Convert int to long (pops int, pushes long - increases stack by 1)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/I2L)
  (-> ctx (pop-stack 1) (push-stack 2)))

(defn emit-l2i
  "Convert long to int (pops long, pushes int - decreases stack by 1)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/L2I)
  (-> ctx (pop-stack 2) (push-stack 1)))

(defn emit-i2f
  "Convert int to float."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/I2F)
  ctx)

(defn emit-i2d
  "Convert int to double (pops int, pushes double - increases stack by 1)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/I2D)
  (-> ctx (pop-stack 1) (push-stack 2)))

(defn emit-f2i
  "Convert float to int."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/F2I)
  ctx)

(defn emit-d2i
  "Convert double to int (pops double, pushes int - decreases stack by 1)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/D2I)
  (-> ctx (pop-stack 2) (push-stack 1)))

;;; --- Stack Manipulation ---

(defn emit-pop
  "Pop top value from stack (discard it)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/POP)
  (pop-stack ctx 1))

(defn emit-pop2
  "Pop top 2 values from stack (or 1 long/double)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/POP2)
  (pop-stack ctx 2))

(defn emit-swap
  "Swap top two values on stack (both must be size 1)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/SWAP)
  ctx)  ; Stack depth unchanged

;;; --- Comparison Operations ---

(defn emit-lcmp
  "Compare two longs (pops 2 longs, pushes int: -1, 0, or 1)."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/LCMP)
  (-> ctx (pop-stack 4) (push-stack 1)))

(defn emit-fcmpl
  "Compare two floats (pops 2 floats, pushes int: -1, 0, or 1).
  Returns -1 on NaN."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/FCMPL)
  (-> ctx (pop-stack 2) (push-stack 1)))

(defn emit-dcmpl
  "Compare two doubles (pops 2 doubles, pushes int: -1, 0, or 1).
  Returns -1 on NaN."
  [ctx]
  (.visitInsn (:method-visitor ctx) Opcodes/DCMPL)
  (-> ctx (pop-stack 4) (push-stack 1)))

;;; --- Helper Functions for Common Patterns ---

(defn emit-if-then-else
  "Emit if-then-else pattern.
  
  condition-fn: function that emits condition and comparison (should leave int on stack for IFEQ)
  then-fn: function that emits 'then' branch
  else-fn: function that emits 'else' branch
  
  Example:
    (emit-if-then-else ctx
      (fn [c] (-> c (emit-load-arg 0) (emit-const 10)))  ; Load x, load 10
      (fn [c] (-> c (emit-const 1) (emit-return)))       ; return 1
      (fn [c] (-> c (emit-const 0) (emit-return))))      ; return 0
  "
  [ctx condition-fn then-fn else-fn]
  (let [else-label (create-label)
        end-label (create-label)]
    (-> ctx
        (condition-fn)
        ;; Compare result (assumes int comparison leaves result on stack)
        (emit-if-le else-label)  ; Jump to else if <=
        (then-fn)
        (emit-goto end-label)
        (mark-label else-label)
        (else-fn)
        (mark-label end-label))))

(defn allocate-local
  "Allocate a new local variable slot, returning [updated-ctx, var-index].
  type should be a symbol like 'int, 'long, etc."
  [ctx type]
  (let [current-index (:local-index ctx)
        slot-size (type-size type)
        next-index (+ current-index slot-size)]
    [(assoc ctx :local-index next-index) current-index]))

(comment
  ;; Phase 7 Examples
  
  ;; Example 1: if-else
  (def if-else-class
    (-> (create-class-builder "IfElseDemo")
        (add-method {:name "max"
                     :params ['int 'int]
                     :return 'int
                     :static? true}
                    (fn [ctx]
                      (let [else-label (create-label)
                            end-label (create-label)]
                        (-> ctx
                            (emit-load-arg 0)  ; Load first arg
                            (emit-load-arg 1)  ; Load second arg
                            (emit-if-le else-label)  ; If first <= second, goto else
                            ;; Then branch: return first arg
                            (emit-load-arg 0)
                            (emit-return)
                            ;; Else branch: return second arg
                            (mark-label else-label)
                            (emit-load-arg 1)
                            (emit-return)
                            (mark-label end-label)))))
        (finalize-class)))
  
  ;; Example 2: Object creation
  (def object-class
    (-> (create-class-builder "ObjectDemo")
        (add-method {:name "createString"
                     :params []
                     :return 'java.lang.String
                     :static? true}
                    (fn [ctx]
                      (-> ctx
                          (emit-new-object "java/lang/String" ['String]
                                          (fn [c] (emit-const c "Hello from bytecode!")))
                          (emit-return))))
        (finalize-class)))
  
  ;; Example 3: Array operations
  (def array-class
    (-> (create-class-builder "ArrayDemo")
        (add-method {:name "sumArray"
                     :params []
                     :return 'int
                     :static? true}
                    (fn [ctx]
                      (-> ctx
                          ;; Create int[3]
                          (emit-const 3)
                          (emit-newarray 'int)
                          ;; Store in local var 0
                          (emit-store-local 0 'java.lang.Object)
                          ;; arr[0] = 10
                          (emit-load-local 0 'java.lang.Object)
                          (emit-const 0)
                          (emit-const 10)
                          (emit-iastore)
                          ;; arr[1] = 20
                          (emit-load-local 0 'java.lang.Object)
                          (emit-const 1)
                          (emit-const 20)
                          (emit-iastore)
                          ;; arr[2] = 30
                          (emit-load-local 0 'java.lang.Object)
                          (emit-const 2)
                          (emit-const 30)
                          (emit-iastore)
                          ;; return arr[0] + arr[1] + arr[2]
                          (emit-load-local 0 'java.lang.Object)
                          (emit-const 0)
                          (emit-iaload)
                          (emit-load-local 0 'java.lang.Object)
                          (emit-const 1)
                          (emit-iaload)
                          (emit-iadd)
                          (emit-load-local 0 'java.lang.Object)
                          (emit-const 2)
                          (emit-iaload)
                          (emit-iadd)
                          (emit-return))))
        (finalize-class)))
  )

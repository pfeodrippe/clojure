(ns clojure.compiler.bootstrap
  "Bootstrap the Clojure compiler to be more dynamic and modifiable at runtime.
   
   This namespace provides hooks into the compilation process that can be
   modified without recompiling the Java code. By routing key compiler
   decisions through Clojure functions, we enable REPL-driven compiler
   development."
  (:import [clojure.lang Compiler]
           [clojure.storm Emitter Utils]))

;; ============================================================================
;; Compiler Hook Vars
;; ============================================================================

(def ^:dynamic *eval-hook*
  "Hook called before evaluating a form.
   Signature: (fn [form] form)
   Should return the (possibly modified) form to evaluate."
  nil)

(def ^:dynamic *compile-hook*
  "Hook called during compilation.
   Signature: (fn [form context] result)
   Can return nil to use default compilation."
  nil)

(def ^:dynamic *macroexpand-hook*
  "Hook called during macroexpansion.
   Signature: (fn [form] form)
   Should return the macroexpanded form."
  nil)

(def ^:dynamic *analyze-hook*
  "Hook called during analysis phase.
   Signature: (fn [form env] analyzed-form)
   Can inspect and transform forms during analysis."
  nil)

(def ^:dynamic *emit-hook*
  "Hook called during bytecode emission.
   Signature: (fn [expr context] nil)
   Called for side effects (logging, instrumentation, etc)."
  nil)

;; ============================================================================
;; Compiler State Management
;; ============================================================================

(defonce compiler-state
  (atom {:forms-compiled 0
         :current-ns nil
         :compilation-stack []
         :last-error nil}))

(defn update-compiler-state!
  "Update compiler state. Called from Java hooks."
  [k f & args]
  (apply swap! compiler-state update k f args))

(defn get-compiler-state
  "Get current compiler state."
  []
  @compiler-state)

(defn reset-compiler-state!
  "Reset compiler state."
  []
  (reset! compiler-state {:forms-compiled 0
                          :current-ns nil
                          :compilation-stack []
                          :last-error nil}))

;; ============================================================================
;; Hook Registration Functions
;; ============================================================================

(defn set-eval-hook!
  "Set the eval hook. The hook function receives the form and should return
   a (possibly modified) form to evaluate."
  [f]
  (alter-var-root #'*eval-hook* (constantly f)))

(defn set-compile-hook!
  "Set the compile hook. The hook function receives the form and context."
  [f]
  (alter-var-root #'*compile-hook* (constantly f)))

(defn set-macroexpand-hook!
  "Set the macroexpand hook."
  [f]
  (alter-var-root #'*macroexpand-hook* (constantly f)))

(defn set-analyze-hook!
  "Set the analyze hook."
  [f]
  (alter-var-root #'*analyze-hook* (constantly f)))

(defn set-emit-hook!
  "Set the emit hook."
  [f]
  (alter-var-root #'*emit-hook* (constantly f)))

(defn clear-hooks!
  "Clear all compiler hooks."
  []
  (set-eval-hook! nil)
  (set-compile-hook! nil)
  (set-macroexpand-hook! nil)
  (set-analyze-hook! nil)
  (set-emit-hook! nil))

;; ============================================================================
;; Hook Application Functions (called from Java)
;; ============================================================================

(defn apply-eval-hook
  "Apply eval hook if present. Called from Compiler.eval()."
  [form]
  (if *eval-hook*
    (try
      (*eval-hook* form)
      (catch Throwable t
        (println "Error in eval hook:" (.getMessage t))
        (.printStackTrace t)
        form))
    form))

(defn apply-compile-hook
  "Apply compile hook if present."
  [form context]
  (when *compile-hook*
    (try
      (*compile-hook* form context)
      (catch Throwable t
        (println "Error in compile hook:" (.getMessage t))
        (.printStackTrace t)
        nil))))

(defn apply-macroexpand-hook
  "Apply macroexpand hook if present."
  [form]
  (if *macroexpand-hook*
    (try
      (*macroexpand-hook* form)
      (catch Throwable t
        (println "Error in macroexpand hook:" (.getMessage t))
        (.printStackTrace t)
        form))
    form))

(defn apply-analyze-hook
  "Apply analyze hook if present."
  [form env]
  (if *analyze-hook*
    (try
      (*analyze-hook* form env)
      (catch Throwable t
        (println "Error in analyze hook:" (.getMessage t))
        (.printStackTrace t)
        form))
    form))

(defn apply-emit-hook
  "Apply emit hook if present."
  [expr context]
  (when *emit-hook*
    (try
      (*emit-hook* expr context)
      (catch Throwable t
        (println "Error in emit hook:" (.getMessage t))
        (.printStackTrace t)))))

;; ============================================================================
;; Compilation Tracking
;; ============================================================================

(defn track-compilation
  "Track a compilation event. Called from Java."
  [event-type data]
  (update-compiler-state! :forms-compiled inc)
  (when (= event-type :start)
    (update-compiler-state! :compilation-stack conj data))
  (when (= event-type :end)
    (update-compiler-state! :compilation-stack pop)))

;; ============================================================================
;; Dynamic Compiler Configuration
;; ============================================================================

(defonce compiler-config
  (atom {:optimize-level 2
         :debug-info true
         :direct-linking false
         :warn-on-reflection true
         :elide-meta #{}
         :custom-passes []}))

(defn set-compiler-config!
  "Update compiler configuration at runtime."
  [k v]
  (swap! compiler-config assoc k v))

(defn get-compiler-config
  "Get current compiler configuration."
  ([]
   @compiler-config)
  ([k]
   (get @compiler-config k)))

;; ============================================================================
;; Instrumentation Control (Enhanced)
;; ============================================================================

(defn set-instrumentation!
  "Enhanced instrumentation control."
  [enabled?]
  (Emitter/setInstrumentationEnable enabled?))

(defn add-instrumentation-prefix!
  "Add namespace prefix to instrument."
  [prefix]
  (Emitter/addInstrumentationOnlyPrefix prefix))

(defn remove-instrumentation-prefix!
  "Remove namespace prefix from instrumentation."
  [prefix]
  (Emitter/removeInstrumentationOnlyPrefix prefix))

;; ============================================================================
;; Form Manipulation
;; ============================================================================

(defn tag-form-with-meta
  "Tag a form with metadata for tracking."
  [form meta-map]
  (if (instance? clojure.lang.IObj form)
    (vary-meta form merge meta-map)
    form))

(defn instrument-form
  "Manually instrument a form (useful for dynamic compilation)."
  [form]
  (if (and form (not (Emitter/skipInstrumentation 
                      (Compiler/munge (str *ns*)))))
    (Utils/tagStormCoord form)
    form))

;; ============================================================================
;; REPL Integration
;; ============================================================================

(defn repl-eval-with-hooks
  "Evaluate form in REPL with all hooks applied."
  [form]
  (let [form' (apply-eval-hook form)]
    (Compiler/eval form')))

;; ============================================================================
;; Example Hooks
;; ============================================================================

(defn logging-eval-hook
  "Example hook that logs all evaluated forms."
  [form]
  (println "EVAL:" (pr-str form))
  form)

(defn timing-eval-hook
  "Example hook that times evaluation."
  [form]
  (let [start (System/nanoTime)]
    (try
      form
      (finally
        (let [elapsed (/ (- (System/nanoTime) start) 1e6)]
          (println "Eval time:" elapsed "ms"))))))

(defn form-rewriting-hook
  "Example hook that can rewrite forms before compilation.
   This demonstrates the power of runtime compiler modification."
  [form]
  (cond
    ;; Example: automatically add profiling to defn
    (and (seq? form) (= 'defn (first form)))
    (let [[_ name & _rest] form]
      (println "Instrumenting defn:" name)
      ;; Could add profiling wrapper here
      form)
    
    ;; Pass through other forms
    :else form))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize the bootstrap system."
  []
  (println "ClojureStorm Bootstrap initialized")
  (println "  - Compiler hooks available")
  (println "  - Runtime reconfiguration enabled")
  (println "  - Form manipulation ready")
  (reset-compiler-state!))

;; Auto-initialize on load
(init!)

;; ============================================================================
;; Public API Summary
;; ============================================================================

(comment
  ;; Set an eval hook to log all evaluations
  (set-eval-hook! logging-eval-hook)
  
  ;; Clear all hooks
  (clear-hooks!)
  
  ;; Check compiler state
  (get-compiler-state)
  
  ;; Modify compiler configuration
  (set-compiler-config! :optimize-level 3)
  
  ;; Manual form instrumentation
  (instrument-form '(defn foo [x] (* x x)))
  
  ;; Control instrumentation dynamically
  (set-instrumentation! false)
  (add-instrumentation-prefix! "my.namespace")
  
  ;; Track compilations
  @compiler-state
  )

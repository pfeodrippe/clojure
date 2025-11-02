(ns clojure.compiler.phase3
  "Phase 3: AST Analysis & Transformation
   
   Working with Abstract Syntax Trees in Clojure:
   - Form analysis and classification
   - AST walking and transformation
   - Macro expansion analysis
   - Scope and binding tracking
   - Code generation helpers"
  (:require [clojure.walk :as walk]))

;; ============================================================================
;; Form Classification
;; ============================================================================

(defn special-form?
  "Check if a symbol is a special form."
  [sym]
  (contains? '#{def if do let loop recur throw try catch finally
                fn* deftype* reify* new quote var set! monitor-enter monitor-exit}
             sym))

(defn def-form?
  "Check if form is a def."
  [form]
  (and (seq? form)
       (= 'def (first form))))

(defn fn-form?
  "Check if form is a fn."
  [form]
  (and (seq? form)
       (or (= 'fn (first form))
           (= 'fn* (first form)))))

(defn let-form?
  "Check if form is a let."
  [form]
  (and (seq? form)
       (contains? '#{let let* loop loop*} (first form))))

(defn if-form?
  "Check if form is an if."
  [form]
  (and (seq? form)
       (= 'if (first form))))

(defn quote-form?
  "Check if form is quoted."
  [form]
  (and (seq? form)
       (= 'quote (first form))))

(defn invoke-form?
  "Check if form is a function invocation."
  [form]
  (and (seq? form)
       (not (special-form? (first form)))))

;; ============================================================================
;; AST Extraction
;; ============================================================================

(defn extract-def-name
  "Extract the name from a def form."
  [form]
  (when (def-form? form)
    (second form)))

(defn extract-def-value
  "Extract the value from a def form."
  [form]
  (when (def-form? form)
    (nth form 2 nil)))

(defn extract-fn-name
  "Extract the name from a fn form (if present)."
  [form]
  (when (fn-form? form)
    (let [second-elem (second form)]
      (when (symbol? second-elem)
        second-elem))))

(defn extract-fn-params
  "Extract parameter list(s) from a fn form."
  [form]
  (when (fn-form? form)
    (let [body (rest form)
          body (if (symbol? (first body)) (rest body) body)] ; Skip name if present
      (cond
        ;; Single arity: (fn [x] ...)
        (vector? (first body))
        [(first body)]
        
        ;; Multi arity: (fn ([x] ...) ([x y] ...))
        (seq? (first body))
        (mapv first body)
        
        :else nil))))

(defn extract-let-bindings
  "Extract bindings from a let form."
  [form]
  (when (let-form? form)
    (second form)))

(defn extract-let-body
  "Extract body from a let form."
  [form]
  (when (let-form? form)
    (drop 2 form)))

(defn extract-if-test
  "Extract test from an if form."
  [form]
  (when (if-form? form)
    (second form)))

(defn extract-if-then
  "Extract then branch from an if form."
  [form]
  (when (if-form? form)
    (nth form 2 nil)))

(defn extract-if-else
  "Extract else branch from an if form."
  [form]
  (when (if-form? form)
    (nth form 3 nil)))

;; ============================================================================
;; Binding Analysis
;; ============================================================================

(defn extract-bindings
  "Extract all binding symbols from a binding vector."
  [bindings]
  (when (vector? bindings)
    (take-nth 2 bindings)))

(defn binding-pairs
  "Convert binding vector to seq of [symbol value] pairs."
  [bindings]
  (when (vector? bindings)
    (partition 2 bindings)))

(defn destructuring-bind?
  "Check if a binding uses destructuring."
  [binding-form]
  (or (vector? binding-form)
      (map? binding-form)))

(defn extract-destructured-symbols
  "Extract all symbols from a destructuring form."
  [binding-form]
  (cond
    (symbol? binding-form)
    [binding-form]
    
    (vector? binding-form)
    (mapcat extract-destructured-symbols binding-form)
    
    (map? binding-form)
    (concat
      (mapcat extract-destructured-symbols (keys binding-form))
      (when-let [as (:as binding-form)] [as])
      (when-let [keys (:keys binding-form)] keys)
      (when-let [syms (:syms binding-form)] syms))
    
    :else []))

(defn find-free-vars
  "Find free variables in a form (variables not bound in the form)."
  [form]
  (let [bound (atom #{})
        free (atom #{})]
    (walk/postwalk
      (fn [x]
        (cond
          ;; Binding form - add to bound set
          (let-form? x)
          (let [bindings (extract-let-bindings x)
                syms (extract-bindings bindings)]
            (swap! bound into syms)
            x)
          
          ;; Function params - add to bound set
          (fn-form? x)
          (let [params (extract-fn-params x)]
            (doseq [param-vec params]
              (swap! bound into param-vec))
            x)
          
          ;; Symbol - check if free
          (symbol? x)
          (do
            (when-not (@bound x)
              (swap! free conj x))
            x)
          
          :else x))
      form)
    @free))

;; ============================================================================
;; AST Walking & Transformation
;; ============================================================================

(defn walk-forms
  "Walk all forms in an expression tree, calling f on each."
  [f form]
  (walk/postwalk f form))

(defn find-forms
  "Find all forms matching a predicate."
  [pred form]
  (let [found (atom [])]
    (walk/postwalk
      (fn [x]
        (when (pred x)
          (swap! found conj x))
        x)
      form)
    @found))

(defn replace-symbol
  "Replace all occurrences of old-sym with new-sym in form."
  [form old-sym new-sym]
  (walk/postwalk-replace {old-sym new-sym} form))

(defn count-invocations
  "Count function invocations of a specific function."
  [fn-sym form]
  (count (find-forms #(and (seq? %) (= fn-sym (first %))) form)))

(defn extract-invocations
  "Extract all invocations of a specific function."
  [fn-sym form]
  (find-forms #(and (seq? %) (= fn-sym (first %))) form))

;; ============================================================================
;; Macro Analysis
;; ============================================================================

(defn macro?
  "Check if a var is a macro."
  [v]
  (and (var? v) (:macro (meta v))))

(defn find-macro-var
  "Try to resolve a symbol to a macro var."
  [sym]
  (try
    (when (symbol? sym)
      (if-let [ns-part (namespace sym)]
        (when-let [ns (find-ns (symbol ns-part))]
          (let [v (ns-resolve ns (symbol (name sym)))]
            (when (macro? v) v)))
        (let [v (ns-resolve *ns* sym)]
          (when (macro? v) v))))
    (catch Exception _ nil)))

(defn macroexpand-safe
  "Safe macroexpand that catches errors."
  [form]
  (try
    (macroexpand form)
    (catch Exception _
      form)))

(defn fully-expand
  "Recursively expand all macros in a form."
  [form]
  (let [expanded (macroexpand-safe form)]
    (if (= expanded form)
      (if (seq? form)
        (map fully-expand form)
        form)
      (fully-expand expanded))))

;; ============================================================================
;; Code Generation Helpers
;; ============================================================================

(defn gen-sym
  "Generate a unique symbol with optional prefix."
  ([] (gensym))
  ([prefix] (gensym prefix)))

(defn gen-let
  "Generate a let form."
  [bindings & body]
  `(let ~bindings ~@body))

(defn gen-if
  "Generate an if form."
  ([test then] `(if ~test ~then))
  ([test then else] `(if ~test ~then ~else)))

(defn gen-fn
  "Generate a fn form."
  ([params & body]
   `(fn ~params ~@body)))

(defn gen-def
  "Generate a def form."
  ([name value]
   `(def ~name ~value))
  ([name doc value]
   `(def ~name ~doc ~value)))

(defn wrap-do
  "Wrap forms in a do block."
  [& forms]
  `(do ~@forms))

(defn wrap-let
  "Wrap form in a let with given bindings."
  [bindings form]
  `(let ~bindings ~form))

;; ============================================================================
;; Scope Analysis
;; ============================================================================

(defn analyze-scope
  "Analyze variable scope in a form.
   Returns a map with :bound (bound vars) and :free (free vars)."
  [form]
  (let [bound (atom #{})
        free (atom #{})
        
        analyze (fn analyze [f scope]
                  (cond
                    ;; Let binding
                    (let-form? f)
                    (let [new-bindings (extract-bindings (extract-let-bindings f))
                          new-scope (into scope new-bindings)]
                      (doseq [body-form (extract-let-body f)]
                        (analyze body-form new-scope)))
                    
                    ;; Function definition
                    (fn-form? f)
                    (let [params (extract-fn-params f)
                          new-scope (into scope (apply concat params))]
                      (doseq [body-form (drop-while #(or (vector? %) (symbol? %)) (rest f))]
                        (analyze body-form new-scope)))
                    
                    ;; Symbol reference
                    (symbol? f)
                    (if (scope f)
                      (swap! bound conj f)
                      (swap! free conj f))
                    
                    ;; Recursive case
                    (coll? f)
                    (doseq [child f]
                      (analyze child scope))))]
    
    (analyze form #{})
    {:bound @bound :free @free}))

(defn capture-scope
  "Get all symbols that would be captured in a closure."
  [fn-form]
  (when (fn-form? fn-form)
    (:free (analyze-scope fn-form))))

;; ============================================================================
;; Demonstration & Testing
;; ============================================================================

(defn demo-form-classification
  "Demonstrate form classification."
  []
  (println "\n📋 Form Classification")
  (println "=" 50)
  
  (let [forms '[def (def x 42)
                fn (fn [x] x)
                let (let [x 1] x)
                if (if true 1 2)
                invoke (println "hi")]]
    (doseq [[label form] (partition 2 forms)]
      (println "  " label ":" form)
      (println "    def?" (def-form? form))
      (println "    fn?" (fn-form? form))
      (println "    let?" (let-form? form))
      (println "    if?" (if-form? form))
      (println "    invoke?" (invoke-form? form)))))

(defn demo-ast-extraction
  "Demonstrate AST extraction."
  []
  (println "\n🔍 AST Extraction")
  (println "=" 50)
  
  (println "\nDef form: (def x 42)")
  (let [form '(def x 42)]
    (println "  name:" (extract-def-name form))
    (println "  value:" (extract-def-value form)))
  
  (println "\nFn form: (fn factorial [n] (if (< n 2) 1 (* n (factorial (dec n)))))")
  (let [form '(fn factorial [n] (if (< n 2) 1 (* n (factorial (dec n)))))]
    (println "  name:" (extract-fn-name form))
    (println "  params:" (extract-fn-params form)))
  
  (println "\nLet form: (let [x 1 y 2] (+ x y))")
  (let [form '(let [x 1 y 2] (+ x y))]
    (println "  bindings:" (extract-let-bindings form))
    (println "  body:" (extract-let-body form)))
  
  (println "\nIf form: (if (< x 10) :small :large)")
  (let [form '(if (< x 10) :small :large)]
    (println "  test:" (extract-if-test form))
    (println "  then:" (extract-if-then form))
    (println "  else:" (extract-if-else form))))

(defn demo-binding-analysis
  "Demonstrate binding analysis."
  []
  (println "\n🔗 Binding Analysis")
  (println "=" 50)
  
  (println "\nSimple bindings: [x 1 y 2]")
  (let [bindings '[x 1 y 2]]
    (println "  symbols:" (extract-bindings bindings))
    (println "  pairs:" (binding-pairs bindings)))
  
  (println "\nDestructuring: [{:keys [a b]} m]")
  (let [binding '{:keys [a b]}]
    (println "  symbols:" (extract-destructured-symbols binding))))

(defn demo-scope-analysis
  "Demonstrate scope analysis."
  []
  (println "\n🎯 Scope Analysis")
  (println "=" 50)
  
  (println "\nClosures and captured variables:")
  (let [forms ['(fn [x] (+ x y))
               '(let [x 1] (fn [] (+ x y)))
               '(fn [x] (let [y 2] (+ x y z)))]]
    (doseq [form forms]
      (println "  " form)
      (let [scope (analyze-scope form)]
        (println "    bound:" (:bound scope))
        (println "    free:" (:free scope))))))

(defn demo-ast-walking
  "Demonstrate AST walking and transformation."
  []
  (println "\n🚶 AST Walking & Transformation")
  (println "=" 50)
  
  (let [form '(defn factorial [n]
                (if (< n 2)
                  1
                  (* n (factorial (dec n)))))]
    
    (println "\nOriginal form:")
    (println " " form)
    
    (println "\nFind all 'factorial invocations:")
    (println " " (extract-invocations 'factorial form))
    
    (println "\nCount invocations:")
    (println "  factorial:" (count-invocations 'factorial form))
    (println "  if:" (count-invocations 'if form))
    
    (println "\nReplace 'n with 'num:")
    (println " " (replace-symbol form 'n 'num))))

(defn demo-code-generation
  "Demonstrate code generation helpers."
  []
  (println "\n⚙️ Code Generation")
  (println "=" 50)
  
  (println "\nGenerated forms:")
  (println "  let:" (gen-let '[x 1 y 2] '(+ x y)))
  (println "  if:" (gen-if '(< x 10) ':small ':large))
  (println "  fn:" (gen-fn '[x] '(* x 2)))
  (println "  def:" (gen-def 'my-var 42)))

(defn run-all-demos
  "Run all Phase 3 demonstrations."
  []
  (println "\n" "╔" (apply str (repeat 68 "═")) "╗")
  (println " ║" (apply str (repeat 68 " ")) "║")
  (println " ║  🌳 Phase 3: AST Analysis & Transformation" (apply str (repeat 25 " ")) "║")
  (println " ║" (apply str (repeat 68 " ")) "║")
  (println " ╚" (apply str (repeat 68 "═")) "╝")
  
  (demo-form-classification)
  (demo-ast-extraction)
  (demo-binding-analysis)
  (demo-scope-analysis)
  (demo-ast-walking)
  (demo-code-generation)
  
  (println "\n" "═" 70)
  (println "✅ Phase 3 Complete!")
  (println "   - Form classification: 7+ predicates")
  (println "   - AST extraction: 10+ functions")
  (println "   - Binding analysis: 5+ functions")
  (println "   - Scope analysis: 3+ functions")
  (println "   - AST walking: 5+ functions")
  (println "   - Code generation: 7+ helpers")
  (println "   Total: ~37 AST manipulation functions!")
  (println "═" 70))

(comment
  ;; Try it out!
  (run-all-demos)
  
  ;; Individual functions
  (fn-form? '(fn [x] x))
  (extract-fn-params '(fn factorial [n] (* n n)))
  (analyze-scope '(fn [x] (+ x y)))
  (find-forms symbol? '(+ x (* y z)))
  )

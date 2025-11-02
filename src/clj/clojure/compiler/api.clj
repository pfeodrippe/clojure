(ns clojure.compiler.api
  "Public API for accessing and manipulating compiler internals from Clojure.
   
   This namespace provides a Clojure-friendly interface to the Java compiler,
   enabling runtime inspection and modification of compilation behavior."
  (:require [clojure.pprint :as pprint])
  (:import [clojure.lang Compiler]
           [clojure.storm Emitter FormRegistry Utils Tracer]))

;; ============================================================================
;; Compiler Access Functions
;; ============================================================================

(defn eval-form
  "Evaluate a form using the compiler. Equivalent to Compiler.eval()."
  [form]
  (Compiler/eval form))

(defn compile-form
  "Compile a form to bytecode without evaluating."
  [form]
  ;; This would need more Java support, but we can start with eval
  (Compiler/eval form))

(defn current-ns
  "Get the current namespace being compiled."
  []
  @clojure.lang.RT/CURRENT_NS)

(defn munge-name
  "Munge a name according to Clojure rules."
  [s]
  (Compiler/munge s))

(defn demunge-name
  "Demunge a name according to Clojure rules."
  [s]
  (Compiler/demunge s))

;; ============================================================================
;; Instrumentation Control
;; ============================================================================

(defn instrumentation-enabled?
  "Check if instrumentation is currently enabled."
  []
  (Emitter/getInstrumentationEnable))

(defn set-instrumentation-enabled!
  "Enable or disable instrumentation."
  [enabled?]
  (Emitter/setInstrumentationEnable enabled?))

(defn instrumentation-only-prefixes
  "Get list of namespace prefixes that will be instrumented."
  []
  (seq (Emitter/getInstrumentationOnlyPrefixes)))

(defn instrumentation-skip-prefixes
  "Get list of namespace prefixes that will NOT be instrumented."
  []
  (seq (Emitter/getInstrumentationSkipPrefixes)))

(defn add-instrumentation-prefix!
  "Add a namespace prefix to instrument."
  [prefix]
  (Emitter/addInstrumentationOnlyPrefix prefix))

(defn remove-instrumentation-prefix!
  "Remove a namespace prefix from instrumentation."
  [prefix]
  (Emitter/removeInstrumentationOnlyPrefix prefix))

(defn skip-instrumentation?
  "Check if a fully-qualified name should skip instrumentation."
  [fq-name]
  (Emitter/skipInstrumentation fq-name))

;; ============================================================================
;; Fine-Grained Instrumentation Control
;; ============================================================================

(defn set-fn-call-instrumentation!
  "Enable/disable function call instrumentation."
  [enabled?]
  (Emitter/setFnCallInstrumentationEnable enabled?))

(defn set-fn-return-instrumentation!
  "Enable/disable function return instrumentation."
  [enabled?]
  (Emitter/setFnReturnInstrumentationEnable enabled?))

(defn set-expr-instrumentation!
  "Enable/disable expression instrumentation."
  [enabled?]
  (Emitter/setExprInstrumentationEnable enabled?))

(defn set-bind-instrumentation!
  "Enable/disable binding instrumentation."
  [enabled?]
  (Emitter/setBindInstrumentationEnable enabled?))

;; ============================================================================
;; Form Registry Access
;; ============================================================================

(defn get-form
  "Get form information by form ID."
  [form-id]
  (when-let [form-map (FormRegistry/getForm form-id)]
    (into {} form-map)))

(defn all-forms
  "Get all registered forms."
  []
  (mapv #(into {} %) (FormRegistry/getAllForms)))

(defn forms-by-namespace
  "Get all forms in a specific namespace."
  [ns-name]
  (filter #(= (str ns-name) (get % :form/ns))
          (all-forms)))

(defn find-forms
  "Find forms matching a predicate."
  [pred]
  (filter pred (all-forms)))

;; ============================================================================
;; Tracer Control
;; ============================================================================

(defn set-trace-callbacks!
  "Set tracing callbacks. Accepts a map with keys:
   :trace-fn-call-fn
   :trace-fn-return-fn  
   :trace-fn-unwind-fn
   :trace-expr-fn
   :trace-bind-fn
   :handle-exception-fn"
  [callbacks]
  (Tracer/setTraceFnsCallbacks callbacks))

(defn clear-trace-callbacks!
  "Clear all trace callbacks."
  []
  (Tracer/setTraceFnsCallbacks {}))

;; ============================================================================
;; Form Manipulation
;; ============================================================================

(defn tag-form-coords
  "Tag a form with Storm coordinates."
  [form]
  (Utils/tagStormCoord form))

(defn strip-storm-meta
  "Strip Storm metadata from a form."
  [form]
  (Utils/stripStormMeta form))

(defn coord-of
  "Get the coordinate of a form from its metadata."
  [form]
  (when-let [coord (Utils/coordOf form)]
    (vec coord)))

;; ============================================================================
;; Dynamic Compilation
;; ============================================================================

(defn compile-string
  "Compile and evaluate a string of Clojure code."
  [code-str]
  (eval-form (read-string code-str)))

(defn compile-with-instrumentation
  "Compile a form with instrumentation temporarily enabled."
  [form]
  (let [old-state (instrumentation-enabled?)]
    (try
      (set-instrumentation-enabled! true)
      (eval-form form)
      (finally
        (set-instrumentation-enabled! old-state)))))

(defn compile-without-instrumentation
  "Compile a form with instrumentation temporarily disabled."
  [form]
  (let [old-state (instrumentation-enabled?)]
    (try
      (set-instrumentation-enabled! false)
      (eval-form form)
      (finally
        (set-instrumentation-enabled! old-state)))))

;; ============================================================================
;; Namespace Operations
;; ============================================================================

(defn reload-namespace!
  "Reload a namespace with current instrumentation settings."
  [ns-sym]
  (require ns-sym :reload))

(defn reload-namespace-with-instrumentation!
  "Reload a namespace with instrumentation enabled."
  [ns-sym]
  (compile-with-instrumentation
   `(require '~ns-sym :reload)))

;; ============================================================================
;; Debugging and Introspection
;; ============================================================================

(defn compiler-info
  "Get information about the current compiler state."
  []
  {:current-ns (str (current-ns))
   :instrumentation-enabled? (instrumentation-enabled?)
   :instrumentation-prefixes (instrumentation-only-prefixes)
   :skip-prefixes (instrumentation-skip-prefixes)
   :total-forms (count (all-forms))})

(defn print-compiler-info
  "Print compiler information."
  []
  (pprint/pprint (compiler-info)))

;; ============================================================================
;; Advanced: Direct Compiler Var Access
;; ============================================================================

(defn get-compiler-var
  "Get a Compiler static var by name (use with caution)."
  [var-name]
  (when-let [field (.getField Compiler var-name)]
    (.setAccessible field true)
    (.get field nil)))

(defn list-compiler-vars
  "List all accessible Compiler static vars."
  []
  (map #(.getName %)
       (filter #(java.lang.reflect.Modifier/isStatic (.getModifiers %))
               (.getDeclaredFields Compiler))))

;; ============================================================================
;; Macro Support
;; ============================================================================

(defmacro with-instrumentation
  "Execute body with instrumentation temporarily enabled."
  [& body]
  `(compile-with-instrumentation
    (fn [] ~@body)))

(defmacro without-instrumentation
  "Execute body with instrumentation temporarily disabled."
  [& body]
  `(compile-without-instrumentation
    (fn [] ~@body)))

(defmacro with-trace
  "Execute body with trace callbacks installed."
  [callbacks & body]
  `(let [old-callbacks# (atom nil)]
     (try
       (set-trace-callbacks! ~callbacks)
       ~@body
       (finally
         (when @old-callbacks#
           (set-trace-callbacks! @old-callbacks#))))))

;; ============================================================================
;; REPL Utilities
;; ============================================================================

(defn repl-info
  "Print useful information for REPL development."
  []
  (println "=== ClojureStorm Compiler API ===")
  (println "\nCompiler State:")
  (print-compiler-info)
  (println "\nUseful functions:")
  (println "  (compiler-info)                    - Get compiler state")
  (println "  (all-forms)                        - Get all compiled forms")
  (println "  (set-instrumentation-enabled! b)   - Enable/disable instrumentation")
  (println "  (add-instrumentation-prefix! s)    - Add namespace to instrument")
  (println "  (reload-namespace-with-instrumentation! 'ns) - Reload with instrumentation")
  (println "\nSee clojure.compiler.bootstrap for hooks"))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize the compiler API."
  []
  (println "ClojureStorm Compiler API initialized")
  (println "Use (repl-info) for usage information"))

;; Auto-initialize
(init!)

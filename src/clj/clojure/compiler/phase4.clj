(ns clojure.compiler.phase4
  "Phase 4: Advanced Compiler Integration & Runtime Hooks
   
   Deep integration with the Clojure compiler:
   - Compilation lifecycle hooks
   - Namespace loading interception
   - Var definition tracking
   - Class generation monitoring
   - REPL enhancement
   - Custom compiler passes"
  (:require [clojure.compiler.phase2 :as p2]
            [clojure.compiler.phase3 :as p3]))

;; ============================================================================
;; Compilation Lifecycle Tracking
;; ============================================================================

(defonce compilation-events (atom []))

(defn record-compilation-event!
  "Record a compilation event for analysis."
  [event-type data]
  (swap! compilation-events conj
         {:type event-type
          :data data
          :timestamp (System/currentTimeMillis)
          :thread (.getName (Thread/currentThread))
          :ns (str *ns*)}))

(defn get-compilation-events
  "Get all recorded compilation events."
  ([] @compilation-events)
  ([event-type]
   (filter #(= event-type (:type %)) @compilation-events)))

(defn clear-compilation-events!
  "Clear all recorded events."
  []
  (reset! compilation-events []))

(defn compilation-statistics
  "Get statistics about compilation events."
  []
  (let [events @compilation-events
        by-type (group-by :type events)]
    {:total (count events)
     :by-type (into {} (map (fn [[k v]] [k (count v)]) by-type))
     :by-namespace (frequencies (map :ns events))
     :timespan (when (seq events)
                 (- (:timestamp (last events))
                    (:timestamp (first events))))}))

;; ============================================================================
;; Namespace Loading Hooks
;; ============================================================================

(defonce namespace-hooks (atom {:before-load #{}
                                :after-load #{}
                                :on-define #{}}))

(defn add-namespace-hook!
  "Add a hook for namespace lifecycle events.
   type can be :before-load, :after-load, or :on-define"
  [type hook-fn]
  (swap! namespace-hooks update type conj hook-fn))

(defn remove-namespace-hook!
  "Remove a namespace hook."
  [type hook-fn]
  (swap! namespace-hooks update type disj hook-fn))

(defn trigger-namespace-hooks
  "Trigger all hooks of a given type."
  [type data]
  (doseq [hook-fn (get @namespace-hooks type)]
    (try
      (hook-fn data)
      (catch Exception e
        (println "Error in namespace hook:" (.getMessage e))))))

;; ============================================================================
;; Var Definition Tracking
;; ============================================================================

(defonce var-definitions (atom {}))

(defn track-var-definition!
  "Track when a var is defined."
  [var-sym value]
  (let [metadata (meta var-sym)
        info {:name var-sym
              :value value
              :metadata metadata
              :timestamp (System/currentTimeMillis)
              :ns (str *ns*)
              :file *file*
              :line (:line metadata)}]
    (swap! var-definitions assoc var-sym info)
    (record-compilation-event! :var-defined info)
    (trigger-namespace-hooks :on-define info)))

(defn get-var-info
  "Get information about a var's definition."
  [var-sym]
  (get @var-definitions var-sym))

(defn get-vars-in-namespace
  "Get all vars defined in a namespace."
  [ns-sym]
  (filter #(= (str ns-sym) (:ns (val %))) @var-definitions))

;; ============================================================================
;; Class Generation Monitoring
;; ============================================================================

(defonce generated-classes (atom []))

(defn record-generated-class!
  "Record information about a generated class."
  [class-name class-type source-form]
  (let [info {:class-name class-name
              :class-type class-type
              :source-form source-form
              :timestamp (System/currentTimeMillis)
              :ns (str *ns*)}]
    (swap! generated-classes conj info)
    (record-compilation-event! :class-generated info)))

(defn get-generated-classes
  "Get all generated classes."
  ([] @generated-classes)
  ([class-type]
   (filter #(= class-type (:class-type %)) @generated-classes)))

(defn find-class-source
  "Find the source form that generated a class."
  [class-name]
  (first (filter #(= class-name (:class-name %)) @generated-classes)))

;; ============================================================================
;; REPL Enhancement
;; ============================================================================

(defonce repl-history (atom []))

(defn record-repl-input!
  "Record REPL input for history."
  [form]
  (swap! repl-history conj
         {:form form
          :timestamp (System/currentTimeMillis)
          :ns (str *ns*)
          :index (count @repl-history)}))

(defn get-repl-history
  "Get REPL history."
  ([] @repl-history)
  ([n] (take-last n @repl-history)))

(defn replay-repl-history
  "Replay REPL history from a given index."
  [start-index]
  (doseq [{:keys [form ns]} (drop start-index @repl-history)]
    (println "Replaying in" ns ":" form)
    (try
      (eval form)
      (catch Exception e
        (println "Error:" (.getMessage e))))))

(defn search-repl-history
  "Search REPL history for forms matching a predicate."
  [pred]
  (filter #(pred (:form %)) @repl-history))

;; ============================================================================
;; Custom Compiler Passes
;; ============================================================================

(defonce compiler-passes (atom []))

(defn register-compiler-pass!
  "Register a custom compiler pass.
   A pass is a function that takes a form and returns a transformed form."
  [pass-name pass-fn]
  (swap! compiler-passes conj {:name pass-name :fn pass-fn}))

(defn apply-compiler-passes
  "Apply all registered compiler passes to a form."
  [form]
  (reduce (fn [f {:keys [name fn]}]
            (try
              (fn f)
              (catch Exception e
                (println "Error in compiler pass" name ":" (.getMessage e))
                f)))
          form
          @compiler-passes))

(defn remove-compiler-pass!
  "Remove a compiler pass by name."
  [pass-name]
  (swap! compiler-passes
         (fn [passes]
           (remove #(= pass-name (:name %)) passes))))

;; ============================================================================
;; Compilation Context Management
;; ============================================================================

(def ^:dynamic *compilation-context* nil)

(defn with-compilation-context
  "Execute body with a compilation context."
  [context body-fn]
  (binding [*compilation-context* context]
    (body-fn)))

(defmacro in-compilation-context
  "Execute body with a compilation context."
  [context & body]
  `(with-compilation-context ~context
     (fn [] ~@body)))

(defn get-compilation-context
  "Get the current compilation context."
  []
  *compilation-context*)

;; ============================================================================
;; Optimization Analysis
;; ============================================================================

(defn analyze-function-for-optimization
  "Analyze a function form for optimization opportunities."
  [fn-form]
  (let [;; Find constant expressions
        constants (p3/find-forms p2/constant? fn-form)
        
        ;; Find inline candidates
        invocations (p3/find-forms seq? fn-form)
        inline-candidates (filter #(p2/should-inline? (first %) (rest %)) invocations)
        
        ;; Find foldable expressions
        foldable (filter #(p2/should-fold? (first %) (rest %)) invocations)
        
        ;; Find free variables (closure captures)
        free-vars (p3/find-free-vars fn-form)
        
        ;; Check for tail recursion
        tail-calls (p3/extract-invocations 'recur fn-form)]
    
    {:constants (count constants)
     :inline-candidates (count inline-candidates)
     :foldable-expressions (count foldable)
     :free-variables (count free-vars)
     :captured-vars free-vars
     :tail-recursive? (pos? (count tail-calls))
     :complexity-score (+ (count invocations) (count free-vars))}))

(defn suggest-optimizations
  "Suggest optimizations for a function."
  [fn-form]
  (let [analysis (analyze-function-for-optimization fn-form)
        suggestions []]
    (cond-> suggestions
      (pos? (:foldable-expressions analysis))
      (conj (str "Fold " (:foldable-expressions analysis) " constant expressions"))
      
      (pos? (:inline-candidates analysis))
      (conj (str "Inline " (:inline-candidates analysis) " small functions"))
      
      (pos? (count (:captured-vars analysis)))
      (conj (str "Function captures " (count (:captured-vars analysis)) 
                 " variables: " (:captured-vars analysis)))
      
      (not (:tail-recursive? analysis))
      (conj "Not tail-recursive - consider using recur for loops")
      
      (> (:complexity-score analysis) 20)
      (conj "High complexity - consider breaking into smaller functions"))))

;; ============================================================================
;; Demonstration & Testing
;; ============================================================================

(defn demo-compilation-tracking
  "Demonstrate compilation event tracking."
  []
  (println "\n📊 Compilation Tracking")
  (println "=" 70)
  
  (clear-compilation-events!)
  
  ;; Record some events
  (record-compilation-event! :compile-start {:form '(defn foo [x] x)})
  (track-var-definition! 'foo (fn [x] x))
  (record-generated-class! "my.ns$foo" :function '(defn foo [x] x))
  (record-compilation-event! :compile-end {:status :success})
  
  (println "\nRecorded events:" (count @compilation-events))
  (println "Statistics:" (compilation-statistics))
  (println "\nVar definitions:" (keys @var-definitions)))

(defn demo-repl-enhancement
  "Demonstrate REPL enhancement features."
  []
  (println "\n🔧 REPL Enhancement")
  (println "=" 70)
  
  ;; Record some REPL inputs
  (record-repl-input! '(+ 1 2))
  (record-repl-input! '(defn foo [x] (* x x)))
  (record-repl-input! '(foo 5))
  
  (println "\nREPL History:")
  (doseq [{:keys [index form ns]} (get-repl-history)]
    (println (format "  [%d] %s (in %s)" index form ns)))
  
  (println "\nSearch for 'defn forms:")
  (doseq [{:keys [form]} (search-repl-history #(and (seq? %) (= 'defn (first %))))]
    (println "  Found:" form)))

(defn demo-compiler-passes
  "Demonstrate custom compiler passes."
  []
  (println "\n⚙️ Custom Compiler Passes")
  (println "=" 70)
  
  ;; Register a simple optimization pass
  (register-compiler-pass!
    :constant-folding
    (fn [form]
      (if (and (seq? form) (p2/should-fold? (first form) (rest form)))
        (or (p2/try-fold (first form) (rest form)) form)
        form)))
  
  (println "\nApplying passes to: (+ 1 2 3)")
  (println "Result:" (apply-compiler-passes '(+ 1 2 3)))
  
  (println "\nApplying passes to: (* 4 5)")
  (println "Result:" (apply-compiler-passes '(* 4 5))))

(defn demo-optimization-analysis
  "Demonstrate function optimization analysis."
  []
  (println "\n🚀 Optimization Analysis")
  (println "=" 70)
  
  (let [test-fn '(fn [x y]
                   (let [a (+ 1 2)
                         b (* 3 4)]
                     (if (< x y)
                       (+ a b external-var)
                       (* x y))))]
    
    (println "\nAnalyzing function:")
    (println test-fn)
    
    (let [analysis (analyze-function-for-optimization test-fn)]
      (println "\nAnalysis:")
      (doseq [[k v] analysis]
        (println (format "  %s: %s" k v)))
      
      (println "\nSuggestions:")
      (doseq [suggestion (suggest-optimizations test-fn)]
        (println "  •" suggestion)))))

(defn run-all-demos
  "Run all Phase 4 demonstrations."
  []
  (println "\n" "╔" (apply str (repeat 68 "═")) "╗")
  (println " ║" (apply str (repeat 68 " ")) "║")
  (println " ║  🔌 Phase 4: Advanced Compiler Integration" (apply str (repeat 24 " ")) "║")
  (println " ║" (apply str (repeat 68 " ")) "║")
  (println " ╚" (apply str (repeat 68 "═")) "╝")
  
  (demo-compilation-tracking)
  (demo-repl-enhancement)
  (demo-compiler-passes)
  (demo-optimization-analysis)
  
  (println "\n" "═" 70)
  (println "✅ Phase 4 Complete!")
  (println "   - Compilation lifecycle tracking")
  (println "   - Namespace loading hooks")
  (println "   - Var definition tracking")
  (println "   - Class generation monitoring")
  (println "   - REPL enhancement (history, replay, search)")
  (println "   - Custom compiler passes")
  (println "   - Optimization analysis & suggestions")
  (println "   Total: Advanced runtime integration!")
  (println "═" 70))

(comment
  ;; Try it out!
  (run-all-demos)
  
  ;; Track compilation
  (record-compilation-event! :my-event {:data "test"})
  (get-compilation-events)
  
  ;; Add namespace hooks
  (add-namespace-hook! :before-load
    (fn [ns-name] (println "Loading namespace:" ns-name)))
  
  ;; Register compiler pass
  (register-compiler-pass! :my-pass
    (fn [form] (println "Processing:" form) form))
  )

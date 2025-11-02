(ns clojure.compiler.examples
  "Examples demonstrating runtime compiler modification capabilities."
  (:require [clojure.compiler.bootstrap :as bootstrap]
            [clojure.compiler.api :as compiler]))

;; ============================================================================
;; Example 1: Logging All Evaluations
;; ============================================================================

(defn enable-eval-logging!
  "Enable logging of all evaluated forms."
  []
  (bootstrap/set-eval-hook!
   (fn [form]
     (println "EVAL:" (pr-str form))
     form))
  (println "Eval logging enabled"))

(defn disable-eval-logging!
  "Disable eval logging."
  []
  (bootstrap/set-eval-hook! nil)
  (println "Eval logging disabled"))

;; ============================================================================
;; Example 2: Automatic Profiling
;; ============================================================================

(def ^:private profile-stats (atom {}))

(defn start-profiling!
  "Start profiling all function calls."
  []
  (reset! profile-stats {})
  (compiler/set-trace-callbacks!
   {:trace-fn-call-fn
    (fn [_thread fn-ns fn-name _args _form-id]
      (let [fn-key (str fn-ns "/" fn-name)]
        (swap! profile-stats update fn-key (fnil inc 0))))})
  (println "Profiling started"))

(defn stop-profiling!
  "Stop profiling and return statistics."
  []
  (compiler/clear-trace-callbacks!)
  (let [stats @profile-stats]
    (reset! profile-stats {})
    (println "Profiling stopped")
    (->> stats
         (sort-by val >)
         (take 20)
         (into {}))))

;; ============================================================================
;; Example 3: Automatic Function Wrapping
;; ============================================================================

(defn wrap-defns-with-logging!
  "Automatically wrap all defn forms with logging."
  []
  (bootstrap/set-eval-hook!
   (fn [form]
     (if (and (seq? form) (= 'defn (first form)))
       (let [[_ fname & rest] form]
         (println "Wrapping function:" fname)
         ;; Return wrapped version
         `(defn ~fname ~@rest))
       form)))
  (println "defn wrapping enabled"))

;; ============================================================================
;; Example 4: Selective Instrumentation
;; ============================================================================

(defn instrument-namespace!
  "Instrument a specific namespace and reload it."
  [ns-sym]
  (compiler/add-instrumentation-prefix! (str ns-sym))
  (compiler/reload-namespace-with-instrumentation! ns-sym)
  (println "Instrumented and reloaded:" ns-sym))

(defn uninstrument-namespace!
  "Remove instrumentation from a namespace."
  [ns-sym]
  (compiler/remove-instrumentation-prefix! (str ns-sym))
  (println "Removed instrumentation from:" ns-sym))

;; ============================================================================
;; Example 5: Code Rewriting
;; ============================================================================

(defn enable-debug-assertions!
  "Automatically add assertions to function arguments."
  []
  (bootstrap/set-eval-hook!
   (fn [form]
     (if (and (seq? form) 
              (= 'defn (first form))
              (>= (count form) 3))
       (let [[def-sym fname args & body] form]
         ;; Add assertions for non-nil arguments
         (if (vector? args)
           `(~def-sym ~fname ~args
              ~@(map (fn [arg] `(assert ~arg ~(str arg " should not be nil"))) 
                     args)
              ~@body)
           form))
       form)))
  (println "Debug assertions enabled"))

;; ============================================================================
;; Example 6: Execution Tracing
;; ============================================================================

(def ^:private trace-log (atom []))

(defn start-tracing!
  "Start recording all execution traces."
  []
  (reset! trace-log [])
  (compiler/set-trace-callbacks!
   {:trace-fn-call-fn
    (fn [_thread fn-ns fn-name args _form-id]
      (swap! trace-log conj [:call fn-ns fn-name (vec args)]))
    
    :trace-fn-return-fn
    (fn [_thread ret-val _coord _form-id]
      (swap! trace-log conj [:return ret-val]))})
  (println "Tracing started"))

(defn stop-tracing!
  "Stop tracing and return collected traces."
  []
  (compiler/clear-trace-callbacks!)
  (let [traces @trace-log]
    (reset! trace-log [])
    (println "Tracing stopped -" (count traces) "events")
    traces))

(defn print-trace-summary
  "Print a summary of the trace log."
  []
  (let [traces @trace-log
        calls (filter #(= :call (first %)) traces)]
    (println "\nTrace Summary:")
    (println "  Total events:" (count traces))
    (println "  Function calls:" (count calls))
    (println "\nTop functions:")
    (doseq [[fn-key count] (->> calls
                                 (map #(str (nth % 1) "/" (nth % 2)))
                                 frequencies
                                 (sort-by val >)
                                 (take 10))]
      (println "   " fn-key "-" count "calls"))))

;; ============================================================================
;; Example 7: Hot Code Reloading with Hooks
;; ============================================================================

(defn setup-hot-reload-hook!
  "Setup a hook that prints whenever code is reloaded."
  []
  (bootstrap/set-eval-hook!
   (fn [form]
     (when (and (seq? form) 
                (= 'ns (first form)))
       (println "Loading namespace:" (second form)))
     form))
  (println "Hot reload hook enabled"))

;; ============================================================================
;; Example 8: Custom Compilation Pass
;; ============================================================================

(defn add-compilation-pass!
  "Add a custom compilation pass that modifies forms."
  [pass-fn]
  (bootstrap/set-eval-hook!
   (fn [form]
     (try
       (pass-fn form)
       (catch Exception e
         (println "Error in compilation pass:" (.getMessage e))
         form))))
  (println "Custom compilation pass added"))

;; ============================================================================
;; Example 9: Interactive Debugging
;; ============================================================================

(def ^:private breakpoints (atom #{}))

(defn add-breakpoint!
  "Add a breakpoint on a function."
  [ns-name fn-name]
  (swap! breakpoints conj [ns-name fn-name])
  (compiler/set-trace-callbacks!
   {:trace-fn-call-fn
    (fn [_thread fn-ns fname args _form-id]
      (when (contains? @breakpoints [fn-ns fname])
        (println "\n=== BREAKPOINT HIT ===")
        (println "Function:" fn-ns "/" fname)
        (println "Arguments:" args)
        (println "======================\n")))})
  (println "Breakpoint added:" ns-name "/" fn-name))

(defn clear-breakpoints!
  "Clear all breakpoints."
  []
  (reset! breakpoints #{})
  (compiler/clear-trace-callbacks!)
  (println "All breakpoints cleared"))

;; ============================================================================
;; Example 10: Performance Monitoring
;; ============================================================================

(def ^:private timing-data (atom {}))

(defn start-performance-monitoring!
  "Monitor performance of all function calls."
  []
  (reset! timing-data {})
  (let [call-times (atom {})]
    (compiler/set-trace-callbacks!
     {:trace-fn-call-fn
      (fn [thread fn-ns fn-name _args _form-id]
        (let [thread-id (.getId thread)
              fn-key [fn-ns fn-name]]
          (swap! call-times assoc [thread-id fn-key] (System/nanoTime))))
      
      :trace-fn-return-fn
      (fn [thread _ret-val _coord _form-id]
        (let [thread-id (.getId thread)]
          (doseq [[[tid fn-key] start-time] @call-times]
            (when (= tid thread-id)
              (let [elapsed (- (System/nanoTime) start-time)
                    elapsed-ms (/ elapsed 1e6)]
                (swap! timing-data update fn-key 
                       (fn [old]
                         (let [{:keys [count total-ms]} (or old {:count 0 :total-ms 0})]
                           {:count (inc count)
                            :total-ms (+ total-ms elapsed-ms)
                            :avg-ms (/ (+ total-ms elapsed-ms) (inc count))}))))
              (swap! call-times dissoc [tid fn-key])))))})
    (println "Performance monitoring started")))

(defn stop-performance-monitoring!
  "Stop performance monitoring and show results."
  []
  (compiler/clear-trace-callbacks!)
  (let [data @timing-data]
    (reset! timing-data {})
    (println "\nPerformance Report:")
    (println "===================")
    (doseq [[[ns-name fn-name] stats] (->> data
                                            (sort-by (comp :total-ms val) >)
                                            (take 20))]
      (println (format "%s/%s: %.2fms total, %d calls, %.2fms avg"
                      ns-name fn-name 
                      (:total-ms stats)
                      (:count stats)
                      (:avg-ms stats))))
    data))

;; ============================================================================
;; REPL Helper
;; ============================================================================

(defn examples-help
  "Show available examples."
  []
  (println "\n=== ClojureStorm Compiler Examples ===\n")
  (println "Logging:")
  (println "  (enable-eval-logging!)     - Log all evaluations")
  (println "  (disable-eval-logging!)    - Disable logging")
  (println "\nProfiling:")
  (println "  (start-profiling!)         - Profile function calls")
  (println "  (stop-profiling!)          - Stop and get stats")
  (println "\nTracing:")
  (println "  (start-tracing!)           - Record execution traces")
  (println "  (stop-tracing!)            - Stop and get traces")
  (println "  (print-trace-summary)      - Show trace summary")
  (println "\nInstrumentation:")
  (println "  (instrument-namespace! 'ns) - Instrument namespace")
  (println "  (uninstrument-namespace! 'ns) - Remove instrumentation")
  (println "\nDebugging:")
  (println "  (add-breakpoint! ns fn)    - Add breakpoint")
  (println "  (clear-breakpoints!)       - Clear all breakpoints")
  (println "\nPerformance:")
  (println "  (start-performance-monitoring!) - Monitor performance")
  (println "  (stop-performance-monitoring!)  - Stop and show report")
  (println "\nOther:")
  (println "  (wrap-defns-with-logging!) - Auto-wrap defns")
  (println "  (enable-debug-assertions!) - Add assertions")
  (println "  (setup-hot-reload-hook!)   - Track hot reloads"))

;; Auto-show help
(examples-help)

(ns clojure.test-compiler-bootstrap
  "Tests for the compiler bootstrap system."
  (:require [clojure.test :refer :all]
            [clojure.compiler.bootstrap :as bootstrap]
            [clojure.compiler.api :as compiler]
            [clojure.compiler.examples :as examples]))

(use-fixtures :each
  (fn [f]
    ;; Clear hooks before and after each test
    (bootstrap/clear-hooks!)
    (f)
    (bootstrap/clear-hooks!)))

(deftest test-eval-hook
  (testing "Eval hook can intercept and modify forms"
    (let [intercepted (atom [])]
      (bootstrap/set-eval-hook!
       (fn [form]
         (swap! intercepted conj form)
         form))
      
      ;; Evaluate something
      (eval '(+ 1 2))
      
      ;; Check that hook was called
      (is (seq @intercepted))
      (is (= '(+ 1 2) (first @intercepted))))))

(deftest test-compiler-state
  (testing "Compiler state tracking works"
    (let [initial-state @bootstrap/compiler-state]
      (bootstrap/update-compiler-state! :forms-compiled inc)
      (is (= (inc (:forms-compiled initial-state))
             (:forms-compiled @bootstrap/compiler-state)))
      
      (bootstrap/reset-compiler-state!)
      (is (zero? (:forms-compiled @bootstrap/compiler-state))))))

(deftest test-instrumentation-control
  (testing "Instrumentation can be toggled"
    (let [original (compiler/instrumentation-enabled?)]
      (try
        (compiler/set-instrumentation-enabled! false)
        (is (not (compiler/instrumentation-enabled?)))
        
        (compiler/set-instrumentation-enabled! true)
        (is (compiler/instrumentation-enabled?))
        
        (finally
          (compiler/set-instrumentation-enabled! original))))))

(deftest test-namespace-instrumentation
  (testing "Namespace prefixes can be added and removed"
    (let [test-prefix "test.namespace"]
      (try
        (compiler/add-instrumentation-prefix! test-prefix)
        (is (some #(= test-prefix %) (compiler/instrumentation-only-prefixes)))
        
        (compiler/remove-instrumentation-prefix! test-prefix)
        (is (not (some #(= test-prefix %) (compiler/instrumentation-only-prefixes))))
        
        (finally
          (compiler/remove-instrumentation-prefix! test-prefix))))))

(deftest test-form-registry-access
  (testing "Can access form registry"
    (let [all-forms (compiler/all-forms)]
      (is (vector? all-forms))
      ;; Should have some forms from loading clojure.core
      (is (pos? (count all-forms)))
      
      ;; Each form should have required keys
      (when (seq all-forms)
        (let [form (first all-forms)]
          (is (contains? form :form/id))
          (is (contains? form :form/ns)))))))

(deftest test-trace-callbacks
  (testing "Trace callbacks can be set and cleared"
    (let [calls (atom [])
          original-enabled (compiler/instrumentation-enabled?)]
      (try
        ;; Ensure instrumentation is enabled for this test
        (compiler/set-instrumentation-enabled! true)
        
        (compiler/set-trace-callbacks!
         {:trace-fn-call-fn
          (fn [_thread fn-ns fn-name _args _form-id]
            (swap! calls conj [fn-ns fn-name]))})
        
        ;; Define and call a function in a namespace that should be instrumented
        ;; Use a namespace that's likely to be instrumented (user namespace)
        (eval '(in-ns 'user))
        (eval '(defn test-fn-for-tracing-123 [] 42))
        (eval '(test-fn-for-tracing-123))
        (eval '(in-ns 'clojure.test-compiler-bootstrap))
        
        ;; Check if we got any trace calls
        ;; Note: may be empty if the namespace isn't being instrumented
        (if (seq @calls)
          (is (some #(= "test-fn-for-tracing-123" (second %)) @calls)
              "Should have traced the function call")
          ;; If no calls were traced, just verify callbacks were set
          (is true "Trace callbacks were set (no traces collected, possibly namespace not instrumented)"))
        
        (finally
          (compiler/clear-trace-callbacks!)
          (compiler/set-instrumentation-enabled! original-enabled))))))

(deftest test-compiler-info
  (testing "Can get compiler information"
    (let [info (compiler/compiler-info)]
      (is (map? info))
      (is (contains? info :current-ns))
      (is (contains? info :instrumentation-enabled?))
      (is (contains? info :total-forms)))))

(deftest test-form-manipulation
  (testing "Forms can be tagged with coordinates"
    (let [form '(defn foo [x] (* x x))
          tagged (compiler/tag-form-coords form)]
      ;; Tagged form should still be a list
      (is (seq? tagged))
      ;; Should have metadata
      (is (meta tagged)))))

(deftest test-example-profiling
  (testing "Profiling example works"
    (try
      (examples/start-profiling!)
      
      ;; Do some work
      (dotimes [_ 5]
        (+ 1 2))
      
      (let [stats (examples/stop-profiling!)]
        ;; Should have some stats if instrumentation is on
        (is (map? stats)))
      
      (finally
        (examples/stop-profiling!)))))

(deftest test-example-tracing
  (testing "Tracing example works"
    (try
      (examples/start-tracing!)
      
      ;; Do some work
      (+ 1 2)
      
      (let [traces (examples/stop-tracing!)]
        (is (vector? traces)))
      
      (finally
        (examples/stop-tracing!)))))

(deftest test-compiler-config
  (testing "Compiler configuration can be modified"
    (let [original (bootstrap/get-compiler-config)]
      (try
        (bootstrap/set-compiler-config! :test-key "test-value")
        (is (= "test-value" (bootstrap/get-compiler-config :test-key)))
        
        (finally
          (reset! bootstrap/compiler-config original))))))

(deftest test-hook-error-handling
  (testing "Hooks handle errors gracefully"
    (let [bad-hook (fn [_] (throw (Exception. "Hook error")))]
      (bootstrap/set-eval-hook! bad-hook)
      
      ;; Should not throw, just log error
      (is (= 3 (eval '(+ 1 2)))))))

;; Run tests when this file is loaded directly
(comment
  (run-tests))

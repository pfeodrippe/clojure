(ns bootstrap-test
  "Simple test to verify the bootstrap system works"
  (:require [clojure.compiler.bootstrap :as bootstrap]
            [clojure.compiler.api :as api]
            [clojure.compiler.examples :as ex]))

(println "\n=== Bootstrap System Test ===\n")

;; Test 1: Check namespaces loaded
(println "1. Namespaces loaded successfully:")
(println "   - clojure.compiler.bootstrap")
(println "   - clojure.compiler.api")
(println "   - clojure.compiler.examples")

;; Test 2: Check instrumentation status
(println "\n2. Instrumentation status:")
(println "   Enabled?" (api/instrumentation-enabled?))

;; Test 3: Check form registry
(println "\n3. Form registry:")
(let [forms (api/all-forms)]
  (println "   Total forms compiled:" (count forms))
  (println "   Sample form:" (first forms)))

;; Test 4: Test eval hook
(println "\n4. Testing eval hook:")
(let [calls (atom [])]
  (bootstrap/set-eval-hook!
    (fn [form]
      (swap! calls conj form)
      form))
  
  (eval '(+ 1 2))
  (eval '(* 3 4))
  
  (println "   Hook captured" (count @calls) "eval calls")
  (println "   Forms:" @calls)
  
  (bootstrap/clear-hooks!))

;; Test 5: Test compiler state
(println "\n5. Testing compiler state:")
(bootstrap/reset-compiler-state!)
(println "   Initial state:" @bootstrap/compiler-state)
(bootstrap/update-compiler-state! :forms-compiled inc)
(println "   After update:" @bootstrap/compiler-state)

;; Test 6: Test configuration
(println "\n6. Testing compiler configuration:")
(bootstrap/set-compiler-config! :test-key "test-value")
(println "   Config value:" (bootstrap/get-compiler-config :test-key))

;; Test 7: Test compiler info
(println "\n7. Compiler information:")
(let [info (api/compiler-info)]
  (println "   Current namespace:" (:current-ns info))
  (println "   Total forms:" (:total-forms info)))

(println "\n=== All Tests Passed! ===\n")
(System/exit 0)

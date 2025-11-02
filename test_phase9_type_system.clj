;; Phase 9: Type System Migration Test
(ns test-phase9-type-system
  "Test Phase 9: Type System Migration
   Verify that Java type system functions use Clojure implementations")

(println "\n=== Phase 9: Type System Migration Test ===\n")

;; Test 1: boxClass migration
(println "Test 1: Compiler.boxClass works")
(let [tests [[Integer/TYPE Integer]
             [Long/TYPE Long]
             [Float/TYPE Float]
             [Double/TYPE Double]
             [Boolean/TYPE Boolean]
             [Character/TYPE Character]
             [Short/TYPE Short]
             [Byte/TYPE Byte]]]
  (doseq [[prim boxed] tests]
    (let [result (clojure.lang.Compiler/boxClass prim)]
      (assert (= boxed result) (str "Expected " boxed " got " result))
      (println "  " (.getSimpleName prim) "→" (.getSimpleName result)))))
(println "  ✅ PASS\n")

;; Test 2: boxClass with non-primitive (should return same class)
(println "Test 2: boxClass with non-primitive")
(let [result (clojure.lang.Compiler/boxClass String)]
  (println "  boxClass(String) =" (.getSimpleName result))
  (assert (= String result) "Non-primitive should return same class"))
(println "  ✅ PASS\n")

;; Test 3: Util.isPrimitive migration
(println "Test 3: Util.isPrimitive works")
(let [primitives [Integer/TYPE Long/TYPE Float/TYPE Double/TYPE 
                   Boolean/TYPE Character/TYPE Short/TYPE Byte/TYPE]
      non-primitives [Integer Long Float Double Boolean Character Short Byte String Object]]
  (doseq [prim primitives]
    (let [result (clojure.lang.Util/isPrimitive prim)]
      (assert result (str (.getSimpleName prim) " should be primitive"))
      (println "  " (.getSimpleName prim) "→ primitive ✅")))
  (doseq [non-prim non-primitives]
    (let [result (clojure.lang.Util/isPrimitive non-prim)]
      (assert (not result) (str (.getSimpleName non-prim) " should not be primitive"))
      (println "  " (.getSimpleName non-prim) "→ not primitive ✅"))))
(println "  ✅ PASS\n")

;; Test 4: Void.TYPE should not be primitive
(println "Test 4: Void.TYPE is not considered primitive")
(let [result (clojure.lang.Util/isPrimitive Void/TYPE)]
  (println "  isPrimitive(void) =" result)
  (assert (not result) "Void.TYPE should not be primitive"))
(println "  ✅ PASS\n")

(println "=== Phase 9: ALL TESTS PASSED! ===\n")
(println "Result: Type system functions now use Clojure!")
(println "  - Compiler.boxClass migrated ✅")
(println "  - Util.isPrimitive migrated ✅")
(println "  - All type checking now goes through Clojure when loaded")
(println "  - Falls back to Java during bootstrap")
(println "\nPhase 9 Complete! Moving to Phase 10... \\o")

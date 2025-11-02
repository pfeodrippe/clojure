(ns test-phase6-add
  (:require [clojure.compiler.phase6 :as bc]))

(println "\n=== Addition Test ===\n")

;; Test: static int add(int a, int b) { return a + b; }
(def add-bytes
  (-> (bc/create-class-builder "AddTest")
      (bc/add-method {:name "add"
                      :params ['int 'int]
                      :return 'int
                      :static? true}
                     (fn [ctx]
                       (println "Context:" ctx)
                       (-> ctx
                           (bc/emit-load-arg 0)
                           (bc/emit-load-arg 1)
                           (bc/emit-iadd)
                           (bc/emit-return))))
      (bc/finalize-class)))

(println "Generated" (alength add-bytes) "bytes")

(def add-class (bc/define-class "AddTest" add-bytes))
(println "Loaded class:" (.getName add-class))

(def add-method (.getMethod add-class "add"
                           (into-array Class [Integer/TYPE Integer/TYPE])))

(println "Testing with Integer objects:")
(println "Result:" (.invoke add-method nil (object-array [(Integer/valueOf 10)
                                                          (Integer/valueOf 32)])))

(System/exit 0)

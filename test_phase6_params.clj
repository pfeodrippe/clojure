(ns test-phase6-params
  (:require [clojure.compiler.phase6 :as bc]))

(println "\n=== Parameter Test ===\n")

;; Test: static int identity(int x) { return x; }
(def identity-bytes
  (-> (bc/create-class-builder "ParamTest")
      (bc/add-method {:name "identity"
                      :params ['int]
                      :return 'int
                      :static? true}
                     (fn [ctx]
                       (println "Context in identity:" ctx)
                       (-> ctx
                           (bc/emit-load-arg 0)
                           (bc/emit-return))))
      (bc/finalize-class)))

(println "Generated" (alength identity-bytes) "bytes")

(def param-class (bc/define-class "ParamTest" identity-bytes))
(println "Loaded class:" (.getName param-class))

(def identity-method (.getMethod param-class "identity"
                                 (into-array Class [Integer/TYPE])))
(println "identity(42):" (.invoke identity-method nil (object-array [(int 42)])))

(System/exit 0)

(ns test-phase6-simple
  (:require [clojure.compiler.phase6 :as bc]))

(println "\n=== Simple Bytecode Test ===\n")

;; Generate the simplest possible class
(def simple-bytes
  (-> (bc/create-class-builder "SimpleTest")
      (bc/add-method {:name "returnFive"
                      :params []
                      :return 'int
                      :static? true}
                     (fn [ctx]
                       (-> ctx
                           (bc/emit-const 5)
                           (bc/emit-return))))
      (bc/finalize-class)))

(println "Generated" (alength simple-bytes) "bytes")

;; Load it
(def simple-class (bc/define-class "SimpleTest" simple-bytes))
(println "Loaded class:" (.getName simple-class))

;; Test it
(def return-five (.getMethod simple-class "returnFive" (into-array Class [])))
(println "Calling returnFive():" (.invoke return-five nil (object-array [])))

(System/exit 0)

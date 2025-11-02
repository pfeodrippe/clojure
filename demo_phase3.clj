(ns demo-phase3
  "Demo script for Phase 3: AST Analysis & Transformation"
  (:require [clojure.compiler.phase3 :as p3]))

(println "\n🌳 Running Phase 3 AST Analysis Demo...\n")

;; Run all demonstrations
(p3/run-all-demos)

;; Additional interactive examples
(println "\n" "═" 70)
(println "🔬 Advanced AST Examples")
(println "═" 70)

(println "\n🎨 Complex Form Analysis:")
(let [complex-form '(defn process-data
                      "Process some data"
                      [items {:keys [limit offset] :or {limit 10}}]
                      (let [filtered (filter valid? items)
                            sorted (sort filtered)
                            paginated (take limit (drop offset sorted))]
                        (map transform paginated)))]
  
  (println "  Form:" complex-form)
  (println "\n  All fn invocations:" (p3/extract-invocations 'fn complex-form))
  (println "  All let forms:" (count (p3/find-forms p3/let-form? complex-form)))
  (println "  Free variables:" (p3/find-free-vars complex-form)))

(println "\n🔍 Closure Analysis:")
(let [closures ['(fn [x] (+ x y))
                '(let [a 1] (fn [b] (+ a b c)))
                '((fn [x] (fn [y] (+ x y z))))]]
  (doseq [closure closures]
    (println "  " closure)
    (println "    captures:" (p3/capture-scope closure))))

(println "\n⚙️ Symbol Replacement:")
(let [form '(defn factorial [n]
              (if (<= n 1)
                1
                (* n (factorial (dec n)))))]
  (println "  Original:")
  (println "   " form)
  (println "\n  After replacing 'n with 'num:")
  (println "   " (p3/replace-symbol form 'n 'num)))

(println "\n🔬 Special Forms Detection:")
(let [forms '[def if do let loop recur throw try fn* new quote var set!]]
  (println "  Special forms detected:")
  (doseq [form forms]
    (println "   " form "→" (p3/special-form? form))))

(println "\n" "═" 70)
(println "✨ Phase 3 Demo Complete!")
(println "   Next up: Phase 4 - Bytecode Generation!")
(println "═" 70)

(ns demo-phase4
  "Demo script for Phase 4: Advanced Compiler Integration"
  (:require [clojure.compiler.phase4 :as p4]))

(println "\n🔌 Running Phase 4: Advanced Compiler Integration Demo...\n")

;; Run all demonstrations
(p4/run-all-demos)

;; Additional interactive examples
(println "\n" "═" 70)
(println "🎯 Additional Advanced Examples")
(println "═" 70)

(println "\n💡 Namespace Hook Example:")
(p4/add-namespace-hook! :before-load
  (fn [data] (println "  🔔 Namespace hook triggered:" data)))

(println "  Hooks registered:" (keys @p4/namespace-hooks))
(println "  Before-load hooks:" (count (:before-load @p4/namespace-hooks)))

(println "\n🔍 Real-World Function Analysis:")
(let [factorial '(defn factorial [n]
                   (if (<= n 1)
                     1
                     (* n (factorial (dec n)))))
      
      complex-fn '(fn [items options]
                    (let [limit (:limit options 10)
                          offset (:offset options 0)
                          filtered (filter valid? items)
                          sorted (sort filtered)]
                      (->> sorted
                           (drop offset)
                           (take limit)
                           (map process))))]
  
  (println "\n  Analyzing factorial:")
  (println "   " factorial)
  (doseq [suggestion (p4/suggest-optimizations factorial)]
    (println "    💡" suggestion))
  
  (println "\n  Analyzing complex function:")
  (println "   " (take 3 complex-fn) "...")
  (doseq [suggestion (p4/suggest-optimizations complex-fn)]
    (println "    💡" suggestion)))

(println "\n📊 Compilation Event Analysis:")
(let [stats (p4/compilation-statistics)]
  (println "  Total events:" (:total stats))
  (println "  Event types:" (:by-type stats))
  (println "  Time span:" (:timespan stats) "ms"))

(println "\n🔄 REPL History Features:")
(println "  Total history entries:" (count (p4/get-repl-history)))
(println "  Recent entries:")
(doseq [{:keys [index form]} (p4/get-repl-history 3)]
  (println (format "    [%d] %s" index form)))

(println "\n⚙️ Compiler Pass System:")
(println "  Registered passes:" (count @p4/compiler-passes))
(println "  Pass names:" (map :name @p4/compiler-passes))

(println "\n  Testing pass on: (+ 10 20 30)")
(println "  Result:" (p4/apply-compiler-passes '(+ 10 20 30)))

(println "\n" "═" 70)
(println "✨ Phase 4 Demo Complete!")
(println "   Advanced compiler integration enables:")
(println "     • Real-time compilation tracking")
(println "     • Custom optimization passes")
(println "     • REPL enhancement and history")
(println "     • Function analysis and suggestions")
(println "     • Namespace lifecycle hooks")
(println "═" 70)

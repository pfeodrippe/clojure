(ns demo-phase2
  "Demo script for Phase 2: Compiler Utilities Migration"
  (:require [clojure.compiler.phase2 :as p2]))

(println "\n🎯 Running Phase 2 Migration Demo...\n")

;; Run all demonstrations
(p2/run-all-demos)

;; Additional interactive examples
(println "\n" "═" 70)
(println "📊 Additional Examples")
(println "═" 70)

(println "\n💡 Tag Inference:")
(doseq [obj [42 3.14 "hello" :keyword 'symbol [1 2 3] {:a 1}]]
  (println "  " obj "→" (p2/infer-tag obj)))

(println "\n⚡ More Constant Folding:")
(println "  (+ 10 20 30) =>" (p2/try-fold '+ [10 20 30]))
(println "  (- 100 50) =>" (p2/try-fold '- [100 50]))
(println "  (* 2 3 4) =>" (p2/try-fold '* [2 3 4]))
(println "  (< 1 5 10) =>" (p2/try-fold '< [1 5 10]))

(println "\n🎨 Expression Classification:")
(doseq [form [42 "string" 'x '(+ 1 2) '(println "hi")]]
  (println "  " form)
  (println "    literal?" (p2/literal? form))
  (println "    simple?" (p2/simple-expression? form))
  (println "    can-emit-primitive?" (p2/can-emit-primitive? form)))

(println "\n🚀 Optimization Decisions:")
(let [test-cases [['+ [1 2]]
                  ['* [3 4 5]]
                  ['println ["hi"]]
                  ['+ '[x y]]
                  ['map '[inc [1 2 3]]]]]
  (doseq [[fn-sym args] test-cases]
    (println "  " (list* fn-sym args))
    (println "    inline?" (p2/should-inline? fn-sym args))
    (println "    fold?" (p2/should-fold? fn-sym args))))

(println "\n" "═" 70)
(println "✨ Phase 2 Demo Complete!")
(println "═" 70)

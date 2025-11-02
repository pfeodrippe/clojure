(ns test-java-to-clojure
  "Test script to demonstrate moving from Java to Clojure"
  (:require [clojure.compiler.java-interop :as ji]))

(println "\n" "=" 70)
(println "🚀 Testing Java-to-Clojure Migration")
(println "=" 70 "\n")

;; Test the migration
(ji/replace-java-munge!)

(println "\n" "-" 70)
(println "Running comprehensive tests...")
(println "-" 70)

(ji/test-migration)

(println "\n" "-" 70)
(println "Installing Clojure-based compiler...")
(println "-" 70)

(ji/install-clojure-compiler!)

(println "\n" "=" 70)
(println "✅ Java-to-Clojure Migration Demo Complete!")
(println "=" 70)

(System/exit 0)

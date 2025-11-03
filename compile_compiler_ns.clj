;; Standalone script to AOT compile compiler namespaces using official Clojure
;; This runs BEFORE building ClojureStorm itself

(println "Compiling compiler namespaces with official Clojure...")

;; Set output directory
(System/setProperty "clojure.compile.path" "target/classes")

;; Compile the compiler namespaces
(compile 'clojure.compiler.java-interop)
(compile 'clojure.compiler.phase2)

(println "Compiler namespaces compiled successfully!")

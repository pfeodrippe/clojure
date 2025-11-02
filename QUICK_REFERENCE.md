# Quick Reference: Java-to-Clojure Migration

## 🚀 Quick Start

```bash
# Compile everything
mvn compile

# Run Phase 1 (Java Interop)
mvn exec:exec -Dexec.executable=java \
  -Dexec.args="-cp %classpath clojure.main test_java_to_clojure.clj"

# Run Phase 2 (Compiler Utilities)
mvn exec:exec -Dexec.executable=java \
  -Dexec.args="-cp %classpath clojure.main demo_phase2.clj"

# Run Phase 3 (AST Analysis)
mvn exec:exec -Dexec.executable=java \
  -Dexec.args="-cp %classpath clojure.main demo_phase3.clj"

# Run all phases
./run_all_phases.sh

# Run tests
mvn -Ptest-direct test
```

---

## 📚 Key Namespaces

### `clojure.compiler.bootstrap`
Bootstrap system for runtime compiler hooks.

```clojure
(require '[clojure.compiler.bootstrap :as bootstrap])

;; Set an eval hook
(bootstrap/set-eval-hook! 
  (fn [form] 
    (println "Evaluating:" form)
    form))

;; Clear all hooks
(bootstrap/clear-hooks!)
```

### `clojure.compiler.api`
50+ functions for compiler access.

```clojure
(require '[clojure.compiler.api :as api])

;; Compiler info
(api/compiler-version)
(api/current-ns)

;; Instrumentation
(api/instrumentation-enabled?)
(api/set-instrumentation-enabled! true)

;; Form registry
(api/all-forms)
(api/forms-by-namespace 'my.ns)
```

### `clojure.compiler.java-interop` (Phase 1)
Pure Clojure implementations of Java functions.

```clojure
(require '[clojure.compiler.java-interop :as ji])

;; Munge/demunge
(ji/munge-name "hello-world")  ; => "hello_world"
(ji/demunge-name "hello_world") ; => "hello-world"

;; Symbol resolution
(ji/resolve-symbol 'map)

;; Migration
(ji/test-migration)
(ji/install-clojure-compiler!)
```

### `clojure.compiler.phase2` (Phase 2)
Compiler utilities: types, optimization, analysis.

```clojure
(require '[clojure.compiler.phase2 :as p2])

;; Type checking
(p2/primitive-type? Integer/TYPE)  ; => true
(p2/box-class Long/TYPE)           ; => Long

;; Constant folding
(p2/try-fold '+ [1 2 3])          ; => 6
(p2/should-fold? '+ [1 2 3])      ; => true

;; Optimization hints
(p2/should-inline? '+ [1 2])      ; => true
(p2/can-emit-primitive? 42)        ; => true

;; Tag inference
(p2/infer-tag [1 2 3])            ; => IPersistentVector
```

### `clojure.compiler.phase3` (Phase 3)
AST analysis and transformation.

```clojure
(require '[clojure.compiler.phase3 :as p3])

;; Form classification
(p3/fn-form? '(fn [x] x))         ; => true
(p3/let-form? '(let [x 1] x))     ; => true

;; AST extraction
(p3/extract-fn-params '(fn [x y] (* x y)))
;; => [[x y]]

(p3/extract-let-bindings '(let [a 1 b 2] (+ a b)))
;; => [a 1 b 2]

;; Scope analysis
(p3/find-free-vars '(fn [x] (+ x y)))
;; => #{y +}

(p3/analyze-scope '(let [x 1] (fn [y] (+ x y z))))
;; => {:bound #{x y}, :free #{z +}}

;; AST transformation
(p3/replace-symbol '(* n n) 'n 'x)
;; => (* x x)

(p3/find-forms symbol? '(+ x (* y z)))
;; => [x y z + *]
```

---

## 🎯 Common Patterns

### Pattern 1: Hook into Compilation
```clojure
(require '[clojure.compiler.bootstrap :as b])

;; Log all compilations
(b/set-compile-hook!
  (fn [form]
    (when (seq? form)
      (println "Compiling:" (first form)))
    form))
```

### Pattern 2: Analyze Code
```clojure
(require '[clojure.compiler.phase3 :as p3])

(defn analyze-function [fn-form]
  {:name (p3/extract-fn-name fn-form)
   :params (p3/extract-fn-params fn-form)
   :free-vars (p3/find-free-vars fn-form)
   :invocations (p3/extract-invocations 'recur fn-form)})

(analyze-function 
  '(fn factorial [n]
     (if (<= n 1)
       1
       (* n (factorial (dec n))))))
```

### Pattern 3: Transform Code
```clojure
(require '[clojure.compiler.phase3 :as p3])

(defn rename-params [fn-form old-name new-name]
  (p3/replace-symbol fn-form old-name new-name))

(rename-params 
  '(fn [x] (* x x))
  'x 'squared)
;; => (fn [squared] (* squared squared))
```

### Pattern 4: Optimize at Compile-Time
```clojure
(require '[clojure.compiler.phase2 :as p2])

(defn optimize-arithmetic [form]
  (if (and (seq? form) (p2/should-fold? (first form) (rest form)))
    (p2/try-fold (first form) (rest form))
    form))

(optimize-arithmetic '(+ 1 2 3))  ; => 6 (computed at compile time!)
```

---

## 🔧 Testing

```bash
# Run all tests
mvn -Ptest-direct test

# Run specific test
mvn -Ptest-direct test -Dtest=clojure.test_clojure.compilation

# Run with coverage
mvn -Ptest-direct test jacoco:report
```

---

## 📊 Function Reference

### Phase 1: Java Interop (11 functions)
- `munge-name` - Munge symbols
- `demunge-name` - Demunge symbols  
- `resolve-symbol` - Resolve symbols
- `maybe-class` - Class lookup
- `analyze-invoke` - Form analysis
- `gen-class-name` - Class name generation
- `replace-java-munge!` - Java replacement
- `test-migration` - Migration testing
- `install-clojure-compiler!` - Install hooks
- `all-tests-passing?` - Test status
- `run-all-demos` - Run demos

### Phase 2: Compiler Utilities (32+ functions)
**Type System:**
- `primitive-type?`, `numeric-type?`
- `box-class`, `unbox-class`
- `widening-conversion?`
- `infer-tag`, `tag-of`, `tag-class`

**Constant Folding:**
- `constant?`, `try-fold`
- `fold-add`, `fold-subtract`, `fold-multiply`, `fold-divide`
- `fold-compare`

**Method Analysis:**
- `static-method?`, `public-method?`
- `find-method`, `method-signature`

**Optimization:**
- `should-inline?`, `should-fold?`
- `can-emit-primitive?`, `can-emit-direct-call?`

### Phase 3: AST Analysis (37+ functions)
**Classification:**
- `special-form?`, `def-form?`, `fn-form?`
- `let-form?`, `if-form?`, `quote-form?`, `invoke-form?`

**Extraction:**
- `extract-def-name`, `extract-def-value`
- `extract-fn-name`, `extract-fn-params`
- `extract-let-bindings`, `extract-let-body`
- `extract-if-test`, `extract-if-then`, `extract-if-else`

**Bindings:**
- `extract-bindings`, `binding-pairs`
- `destructuring-bind?`, `extract-destructured-symbols`
- `find-free-vars`

**Scope:**
- `analyze-scope`, `capture-scope`

**Transformation:**
- `walk-forms`, `find-forms`, `replace-symbol`
- `count-invocations`, `extract-invocations`

**Generation:**
- `gen-sym`, `gen-let`, `gen-if`, `gen-fn`, `gen-def`
- `wrap-do`, `wrap-let`

---

## 💡 Tips

1. **Start Small**: Begin with Phase 1, understand the foundation
2. **Use the REPL**: All functions work interactively
3. **Read Tests**: `test/clojure/test_compiler_bootstrap.clj` has examples
4. **Check Docs**: Each namespace has detailed docstrings
5. **Run Demos**: Each phase has a demo script showing usage

---

## 🐛 Troubleshooting

**Q: Compilation fails?**  
A: Run `mvn clean compile` to rebuild from scratch.

**Q: Tests failing?**  
A: Make sure you're on the `bootstrap` branch.

**Q: Hooks not working?**  
A: Ensure bootstrap namespace is loaded: `(require 'clojure.compiler.bootstrap)`

**Q: Performance issues?**  
A: Hooks add <2% overhead. Disable instrumentation: `(api/set-instrumentation-enabled! false)`

---

## 📖 Further Reading

- `BOOTSTRAP.md` - Bootstrap system details
- `BOOTSTRAP_SUCCESS.md` - Implementation notes
- `JAVA_TO_CLOJURE_MIGRATION.md` - Migration strategy
- `PHASE_SUMMARY.md` - Complete phase overview

---

**Happy Hacking!** 🚀

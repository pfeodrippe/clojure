# Clojure Compiler Bootstrap System

## Overview

The ClojureStorm compiler now includes a **bootstrap system** that enables runtime modification of the compiler through Clojure code, rather than requiring Java recompilation. This makes the compiler more dynamic and hackable.

## Architecture

The bootstrap system consists of three layers:

### 1. Bootstrap Layer (`clojure.compiler.bootstrap`)

Provides the core hook system using dynamic vars:

- `*eval-hook*` - Intercept and transform forms before eval
- `*compile-hook*` - Hook into compilation process
- `*macroexpand-hook*` - Intercept macro expansion
- `*analyze-hook*` - Hook into analysis phase
- `*emit-hook*` - Hook into bytecode emission

### 2. API Layer (`clojure.compiler.api`)

Friendly interface to compiler internals:

```clojure
(require '[clojure.compiler.api :as compiler])

;; Eval and compilation
(compiler/eval-form '(+ 1 2))
(compiler/current-ns)

;; Instrumentation control
(compiler/set-instrumentation-enabled! true)
(compiler/add-instrumentation-prefix! "my.namespace")

;; Form registry
(compiler/all-forms)
(compiler/forms-by-namespace "clojure.core")

;; Tracing
(compiler/set-trace-callbacks! {...})
```

### 3. Examples Layer (`clojure.compiler.examples`)

10 practical examples demonstrating capabilities:

1. **Eval Logging** - Log every eval call
2. **Automatic Profiling** - Profile function calls
3. **Function Wrapping** - Wrap defns with logging
4. **Selective Instrumentation** - Control what gets instrumented
5. **Code Rewriting** - Add debug assertions
6. **Execution Tracing** - Trace function calls
7. **Compile-Time Validation** - Custom validation
8. **Macro Expansion Debugging** - Debug macros
9. **Interactive Debugging** - Breakpoints in code
10. **Performance Monitoring** - Monitor hot functions

## Usage

### Quick Start

```clojure
;; Enable eval logging
(require '[clojure.compiler.examples :as ex])
(ex/enable-eval-logging!)

;; Now every eval will be logged
(+ 1 2)
;; => Eval: (+ 1 2)

;; Start profiling
(ex/start-profiling!)
(defn my-fn [x] (* x x))
(my-fn 5)
(ex/stop-profiling!)
;; => Shows timing stats for my-fn

;; Enable tracing
(ex/start-tracing!)
(defn factorial [n]
  (if (<= n 1)
    1
    (* n (factorial (dec n)))))
(factorial 5)
(ex/stop-tracing!)
;; => Shows all function calls with arguments
```

### Custom Hooks

```clojure
(require '[clojure.compiler.bootstrap :as bootstrap])

;; Intercept all evals and transform them
(bootstrap/set-eval-hook!
  (fn [form]
    (println "About to eval:" form)
    ;; Return modified form or original
    form))

;; Clear all hooks
(bootstrap/clear-hooks!)
```

### Instrumentation Control

```clojure
(require '[clojure.compiler.api :as compiler])

;; Disable instrumentation for better performance
(compiler/set-instrumentation-enabled! false)

;; Enable only for specific namespaces
(compiler/set-instrumentation-enabled! true)
(compiler/add-instrumentation-prefix! "my.app")
(compiler/add-instrumentation-prefix! "my.lib")

;; Now only my.app.* and my.lib.* will be instrumented
```

### Accessing Form Registry

```clojure
;; Get all compiled forms
(def all-forms (compiler/all-forms))

;; Get forms for a specific namespace
(def core-forms (compiler/forms-by-namespace "clojure.core"))

;; Get a specific form by ID
(def form (compiler/get-form 42))

;; Analyze form metadata
(println (:form/source form))
(println (:form/ns form))
(println (:form/line form))
```

### Custom Tracing

```clojure
;; Set up custom trace callbacks
(compiler/set-trace-callbacks!
  {:trace-fn-call-fn
   (fn [thread fn-ns fn-name args form-id]
     (println "CALL:" fn-ns "/" fn-name "with" args))
   
   :trace-fn-return-fn
   (fn [thread fn-ns fn-name result form-id]
     (println "RETURN:" fn-ns "/" fn-name "=>" result))
   
   :trace-fn-unwind-fn
   (fn [thread fn-ns fn-name exception form-id]
     (println "EXCEPTION:" fn-ns "/" fn-name exception))})

;; Trace some code
(defn my-traced-fn [x]
  (+ x 1))

(my-traced-fn 5)
;; => CALL: user / my-traced-fn with [5]
;; => RETURN: user / my-traced-fn => 6

;; Clear callbacks
(compiler/clear-trace-callbacks!)
```

## Integration with Java Compiler

The bootstrap system is integrated into `clojure.lang.Compiler` via the `applyBootstrapHook` method:

```java
private static Object applyBootstrapHook(String hookName, Object form) {
    try {
        Var hookVar = Var.find(Symbol.create("clojure.compiler.bootstrap", hookName));
        if (hookVar != null && hookVar.isBound()) {
            Object hookFn = hookVar.deref();
            if (hookFn instanceof IFn) {
                return ((IFn)hookFn).invoke(form);
            }
        }
    } catch (Exception e) {
        System.err.println("Error applying bootstrap hook " + hookName + ": " + e.getMessage());
    }
    return form;
}
```

This is called at strategic points in the compiler (currently in `eval`).

## Bootstrap Process

1. **Initial Compilation**: During the initial build (`mvn compile`), the Java compiler is compiled first.
2. **Hook Errors**: You'll see "No such namespace: clojure.compiler.bootstrap" errors during initial bootstrap - this is expected and harmless.
3. **Namespace Loading**: Once `clojure.compiler.bootstrap` is compiled, the hooks become available.
4. **Runtime Usage**: In a running REPL, you can require the bootstrap namespaces and use the hooks immediately.

## Performance Considerations

- **Hook Overhead**: Hooks add a small overhead to eval/compile operations. Only use when needed.
- **Instrumentation**: Storm instrumentation has its own overhead. Disable it for production or use selective prefixes.
- **Tracing**: Trace callbacks can generate significant output. Use sparingly or with filters.

## Best Practices

1. **Clear Hooks**: Always clear hooks when done to avoid unexpected behavior:
   ```clojure
   (bootstrap/clear-hooks!)
   ```

2. **Selective Instrumentation**: Use prefixes to instrument only what you need:
   ```clojure
   (compiler/add-instrumentation-prefix! "my.app.core")
   ```

3. **Error Handling**: Hooks should handle errors gracefully:
   ```clojure
   (bootstrap/set-eval-hook!
     (fn [form]
       (try
         (my-custom-logic form)
         form
         (catch Exception e
           (println "Hook error:" e)
           form))))  ;; Return original on error
   ```

4. **Performance**: Profile your hooks if they're in hot paths:
   ```clojure
   (time
     (dotimes [_ 1000]
       (eval '(+ 1 2))))
   ```

## Testing

Run the bootstrap tests:

```bash
mvn -Ptest-direct test -Dtest=clojure.test_compiler_bootstrap
```

Or run all tests:

```bash
mvn -Ptest-direct test
```

## Examples

See `src/clj/clojure/compiler/examples.clj` for 10 comprehensive examples of what you can do with the bootstrap system.

## Future Enhancements

Potential areas for expansion:

- **More Hook Points**: Add hooks for reader, analyzer, emitter phases
- **Hot Reloading**: Integrate with tools.namespace for seamless reloading
- **Debug Protocol**: Build a debug protocol on top of the hooks
- **Optimization Hooks**: Allow custom optimizations
- **Plugin System**: Formalize a plugin architecture

## Differences from Upstream Clojure

The bootstrap system is a **ClojureStorm-only** feature. Upstream Clojure does not have:

- Runtime compiler modification hooks
- Dynamic var-based compiler configuration
- Clojure API for accessing compiler internals
- Integration points for custom compiler behavior

This makes ClojureStorm more suitable for:

- Development tooling (debuggers, profilers)
- Runtime code transformation
- Research and experimentation
- Teaching compiler internals

## Contributing

When adding new hooks or API functions:

1. Add the hook/function to the appropriate namespace
2. Document it in this file
3. Add examples to `examples.clj`
4. Add tests to `test_compiler_bootstrap.clj`
5. Update `DOC.md` if it affects the overall architecture

## Questions?

- Check `DOC.md` for overall ClojureStorm architecture
- Check `examples.clj` for working examples
- Check the tests for usage patterns
- Open an issue if you find bugs or have feature requests

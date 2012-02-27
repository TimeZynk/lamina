;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.trace.instrument
  (:use
    [potemkin]
    [lamina core]
    [lamina.trace.probe :only (probe-channel probe-enabled?)])
  (:require
    [lamina.core.context :as context]
    [lamina.executor.utils :as ex]
    [lamina.trace.timer :as t]))

(defrecord Enter [^long timestamp args])

(defmacro instrument-task-body
  [nm executor enter-probe return-probe implicit? timeout invoke args]
  `(do
     (when (probe-enabled? ~enter-probe)
       (enqueue ~enter-probe (Enter. (System/currentTimeMillis) ~args)))
     (let [timer# (t/enqueued-timer ~nm ~args ~return-probe nil ~implicit?)]
       (ex/execute ~executor timer# (fn [] ~invoke) ~(when timeout `(when ~timeout (~timeout ~args)))))))

(defn instrument-task
  [f & {:keys [executor timeout implicit?]
        :as options
        :or {implicit? true}}]
  (let [nm (name (:name options))
        enter-probe (probe-channel [nm :enter])
        return-probe (probe-channel [nm :return])
        error-probe (probe-channel [nm :error])]
    (fn
      ([]
         (instrument-task-body nm executor enter-probe return-probe implicit? timeout
           (f) [])) 
      ([a]
         (instrument-task-body nm executor enter-probe return-probe implicit? timeout
           (f a) [a]))
      ([a b]
         (instrument-task-body nm executor enter-probe return-probe implicit? timeout
           (f a b) [a b]))
      ([a b c]
         (instrument-task-body nm executor enter-probe return-probe implicit? timeout
           (f a b c) [a b c]))
      ([a b c d]
         (instrument-task-body nm executor enter-probe return-probe implicit? timeout
           (f a b c d) [a b c d]))
      ([a b c d e] 
         (instrument-task-body nm executor enter-probe return-probe implicit? timeout
           (f a b c d e) [a b c d e]))
      ([a b c d e & rest]
         (instrument-task-body nm executor enter-probe return-probe implicit? timeout
           (apply f a b c d e rest) (list* a b c d e rest))))))

(defmacro instrument-body [nm enter-probe return-probe implicit? invoke args]
  `(do
     (when (probe-enabled? ~enter-probe)
       (enqueue ~enter-probe (Enter. (System/currentTimeMillis) ~args)))
     (let [timer# (t/timer ~nm ~args ~return-probe nil ~implicit?)]
       (context/with-context (context/assoc-context :timer timer#)
         (try
           (let [result# ~invoke]
             (run-pipeline result#
               {:error-handler (fn [err#] (t/mark-error timer# err#))}
               (fn [x#] (t/mark-return timer# x#)))
             result#)
           (catch Exception e#
             (t/mark-error timer# e#)
             (throw e#)))))))

(defn instrument
  "A general purpose transform for functions, allowing for tracing their execution, defining
   timeouts, and deferring their execution onto a thread pool.  Instrumented functions always
   return result-channels.

   ---------
   OVERHEAD

   Instrumenting adds some overhead to a function, equivalent to the performance difference
   between calling

     (+ 1 2 3)

   and

     (apply + [1 2 3])

   If you'd happily call 'apply' on the function being instrumented, chances are you won't
   notice the difference.

   ---------
   PROBES

   Instrumenting a function creates 'enter', 'return', and 'error' probes.  A :name must be
   specified, and probes will be of the structure name:enter, name:return, etc.  Data emitted by
   these probes may be captured by other functions if :implicit? is set to true, which is the
   default.

   When the function is invoked, the 'enter' probe emits a hash of the form

     :description - the :name specified in the options
     :timestamp   - time of invocation in milliseconds since the epoch
     :args        - a list of arguments passed to the function

   When the function completes and the value is realized, the 'return' probe
   will emit the data above, and also:

     :duration    - the time elapsed since the invocation, in nanoseconds
     :result      - the value returned by the function
     :sub-tasks   - 'return' probe data, less :result, for all implicit instrumented sub-functions
     
   If an error is thrown, or the value is realized as an error, :result is replaced by

     :error       - the exception thrown or realized error

   A :probes option may be defined, giving a hash of probe names onto channels that will consume
   their data:

     {:error (siphon->> (sink #(println \"ERROR:\" %)))
      :return (siphon->> (sink #(println \"Called with\" (:args %) \"and returned\" (:result %))))}

  ----------
  TIMEOUTS

  A :timeout option may be specified, which should be a function that takes the arguments passed
  to the function, and returns the timeout in milliseconds or nil for no timeout.  If the timeout
  elapses without any result, the returned result-channel will resolve as an error of type
  :lamina/timeout!

  ----------
  EXECUTORS

  If an :executor is specified, the function will be executed on that thread pool.  In this case,
  :timeout will also interrupt the thread if it is still actively computing the result, and the
  'return' probe will include an :enqueued-duration parameter that describes the time, in
  nanoseconds, spent waiting to be executed."
  [f & {:keys [executor timeout implicit? probes]
        :as options
        :or {implicit? true}}]
  (when-not (contains? options :name)
    (throw (IllegalArgumentException. "Instrumented functions must have a :name defined.")))
  (if executor
    (apply instrument-task f (apply concat options))
    (let [nm (name (:name options))
          enter-probe (probe-channel [nm :enter])
          return-probe (probe-channel [nm :return])
          error-probe (probe-channel [nm :error])]
      (doseq [[k v] probes]
        (siphon (probe-channel [~nm k]) v))
      (fn
        ([]
           (instrument-body nm enter-probe return-probe implicit?
             (f) []))
        ([a]
           (instrument-body nm enter-probe return-probe implicit?
             (f a) [a]))
        ([a b]
           (instrument-body nm enter-probe return-probe implicit?
             (f a b) [a b]))
        ([a b c]
           (instrument-body nm enter-probe return-probe implicit?
             (f a b c) [a b c]))
        ([a b c d]
           (instrument-body nm enter-probe return-probe implicit?
             (f a b c d) [a b c d]))
         ([a b c d e] 
           (instrument-body nm enter-probe return-probe implicit?
             (f a b c d e) [a b c d e]))
        ([a b c d e & rest]
           (instrument-body nm enter-probe return-probe implicit?
             (apply f a b c d e rest) (list* a b c d e rest)))))))

;;;

(defmacro defn-instrumented
  "A def form of (instrument...). Options can be defined in the function metadata:

   (defn-instrumented foo
     {:implicit? false
      :timeout (constantly 1000)}
     \"Here's a doc-string\"
     [x]
     ...)

   The :name can be explicitly defined, but will default to

     the:function:namespace:the-function-name

   "
  [fn-name & body]
  (let [form `(defn ~fn-name ~@body)
        form (macroexpand form)
        mta (merge
              {:implicit? true}
              (meta (second form)))
        nm (or (:name mta)
             (str (-> (ns-name *ns*) str (.replace \. \:)) ":" (name fn-name)))
        executor? (contains? mta :executor)
        implicit? (:implicit? mta)
        timeout (:timeout mta)]
    (unify-gensyms
      `(let [enter-probe## (probe-channel [~nm :enter])
             return-probe## (probe-channel [~nm :return])
             error-probe## (probe-channel [~nm :error])
             executor## ~(:executor mta)
             implicit?## ~(:implicit? mta)]
         (doseq [[k# v#] ~(:probes mta)]
           (siphon (probe-channel [~nm k#]) v#))
         ~(transform-defn-bodies
            (fn [args body]
              (if executor?
                `((instrument-task-body ~nm executor## enter-probe## return-probe## ~implicit? ~timeout
                    (do ~@body) ~args))
                `((instrument-body ~nm enter-probe## return-probe## ~implicit?
                    (do ~@body) ~args))))
            form)))))
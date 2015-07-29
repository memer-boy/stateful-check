(ns stateful-check.core
  (:require [clojure.test.check
             [generators :as gen]
             [properties :refer [for-all]]
             [rose-tree :as rose]]
            [stateful-check
             [command-runner :as r]
             [command-verifier :as v]
             [gen :refer [gen-do]]
             [symbolic-values :as symbolic-values :refer [->RootVar SymbolicValue]]]))

(defmacro ^:private assert-val
  ([val]
   (when *assert*
     `(let [val# ~val]
        (if (some? val#)
          val#
          (throw (new AssertionError (str "Assert failed: " (pr-str '~val))))))))
  ([val message]
   (when *assert*
     `(let [val# ~val]
        (if (some? val#)
          val#
          (throw (new AssertionError (str "Assert failed: " ~message))))))))

(def ^:private setup-symbolic-var
  "A symbolic value used to track the result of calling the `setup`
  function during command generation and execution."
  (->RootVar "setup"))

(defn ^:private generate-command-object [spec state]
  (gen/gen-fmap (fn [rose-tree]
                  (let [command-key (rose/root rose-tree)]
                    (assoc (assert-val (get (:commands spec) command-key)
                                       (str "Command " command-key " not found in :commands map"))
                           :name command-key)))
                ((:model/generate-command spec) state)))

(defn ^:private generate-commands*
  "Returns a list of rose-trees within the monad of gen/gen-pure. "
  ([spec state] (generate-commands* spec state 0))
  ([spec state count]
   (gen/sized
    (fn [size]
      (gen/frequency
       [[1 (gen/gen-pure nil)]
        [size (gen-do command <- (generate-command-object spec state)
                      rose <- (if-let [f (:model/args command)]
                                (f state)
                                (gen/return []))
                      :let [args (rose/root rose)]
                      (if ((or (:model/precondition command)
                               (constantly true))
                           state args)
                        (gen-do :let [result (->RootVar count)
                                      next-state (or (:model/next-state command)
                                                     (:next-state command)
                                                     (constantly state))]
                                roses <- (gen/resize (dec size)
                                                     (generate-commands* spec (next-state state args result) (inc count)))
                                (gen/gen-pure (cons (rose/fmap (fn [args]
                                                                 [result (cons command args)])
                                                               rose)
                                                    roses)))
                        (generate-commands* spec state count)))]])))))

(defn ^:private concat-command-roses
  "Take a seq of rose trees and concatenate them. Create a vector from
  the roots along with a rose tree consisting of shrinking each
  element in turn."
  [roses]
  (if (seq roses)
    [(apply vector (map rose/root roses))
     (map concat-command-roses (rose/remove (vec roses)))]
    [[] []]))

(defn ^:private generate-commands
  "Generate a seq of commands from a spec and an initial state."
  [spec state]
  (gen/gen-bind (generate-commands* spec state)
                (fn [roses]
                  (gen/gen-pure (concat-command-roses roses)))))

(defn ^:private run-commands
  "Run a seq of commands against a live system."
  [spec command-list]
  (let [state-fn (or (:real/initial-state spec)
                     (:initial-state spec)
                     (constantly nil))
        setup-fn (:real/setup spec)
        setup-value (if setup-fn (setup-fn))
        results (if setup-fn
                  {setup-symbolic-var setup-value})
        state (if setup-fn
                (state-fn setup-value)
                (state-fn))
        command-results (r/run-commands command-list results state)]
    (if-let [f (:real/cleanup spec)]
      (f (last (r/extract-states command-results))))
    command-results))

(defn ^:private valid-commands?
  "Verify whether a given list of commands is valid (preconditions all
  return true, symbolic vars are all valid, etc.)."
  [spec command-list]
  (v/valid? command-list
            (if (:real/setup spec)
              #{setup-symbolic-var}
              #{})
            (let [initial-state-fn (or (:model/initial-state spec)
                                       (:initial-state spec)
                                       (constantly nil))]
              (if (:real/setup spec)
                (initial-state-fn setup-symbolic-var)
                (initial-state-fn)))))

(defn ^:private generate-valid-commands
  "Generate a set of valid commands from a specification"
  [spec]
  (->> (let [initial-state-fn (or (:model/initial-state spec)
                                  (:initial-state spec)
                                  (constantly nil))]
         (if (:real/setup spec)
           (initial-state-fn setup-symbolic-var)
           (initial-state-fn)))
       (generate-commands spec)
       (gen/such-that (partial valid-commands? spec))))

(defn reality-matches-model
  "Create a property which checks a given stateful-check
  specification."
  [spec]
  (for-all [commands (generate-valid-commands spec)]
    (let [command-results (run-commands spec commands)
          ex (r/extract-exception command-results)]
      (cond (r/passed? command-results) true
            ex (throw ex)
            :else false))))

(defn ^:private print-command-results
  "Print out the results of a `run-commands` call. No commands are
  actually run, as the argument to this function contains the results
  for each individual command."
  [results]
  (try
    (doseq [[type :as step] results]
      (case type
        :postcondition-check
        (let [[_ [[sym-var [{name :name} _ args]]] _ prev-state _ result] step]
          (println "  " sym-var "=" (cons name args) "\t=>" result))
        :fail
        (if-let [ex (second step)]
          (println "  " ex)
          (println "   !! postcondition violation"))
        nil))
    (catch Throwable ex
      (println "   !! exception thrown")
      (.printStackTrace ex *out*))))

(defn print-test-results
  "Print the results of a test.check test in a more helpful form (each
  command with its corresponding output).

  This function will re-run both the failing test case and the shrunk
  failing test. This means that the commands will be run against the
  live system."
  [spec results]
  (when-not (true? (:result results))
    (println "\nFailing test case:")
    (print-command-results (run-commands spec (-> results :fail first)))
    (println "Shrunk:")
    (print-command-results (run-commands spec (-> results :shrunk :smallest first)))))

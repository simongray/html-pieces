(ns dk.simongray.html-pieces.bench.harness
  "The time of a function call, measured the same way on the JVM and in
  Node: calls for a while to warm up, then samples of many calls, whose
  median is the time per call. Every benchmark is measured in a few
  rounds, and its fastest round counts.")

(def sink
  "The result of the last call, kept so that no call is optimized away."
  (volatile! nil))

(def default-opts
  "How long to warm up and to sample, in milliseconds, how many samples to
  take, and in how many rounds."
  {:warmup-ms 1000
   :sample-ms 200
   :samples   5
   :rounds    2})

(def quick-opts
  "Options for a quick run, to check that every benchmark works."
  {:warmup-ms 100
   :sample-ms 20
   :samples   3
   :rounds    1})

(defn now-ns
  "The current time of a monotonic clock in nanoseconds."
  []
  #?(:clj  (System/nanoTime)
     :cljs (* 1e6 (js/performance.now))))

(defn calls
  "Call `f` with `x` `n` times, and give the nanoseconds that it took."
  [f x n]
  (let [start (now-ns)]
    (dotimes [_ n]
      (vreset! sink (f x)))
    (- (now-ns) start)))

(defn warm-up
  "Call `f` with `x` for at least `ms` milliseconds, and give the time per
  call in nanoseconds."
  [f x ms]
  (loop [n 0 elapsed 0]
    (if (< elapsed (* ms 1e6))
      (recur (inc n) (+ elapsed (calls f x 1)))
      (/ elapsed n))))

(defn median
  "The median of the numbers `xs`."
  [xs]
  (let [sorted (vec (sort xs))
        n      (count sorted)]
    (if (odd? n)
      (sorted (quot n 2))
      (/ (+ (sorted (dec (quot n 2))) (sorted (quot n 2))) 2))))

(defn time-per-call
  "The time of a call of `f` with `x`, measured by `opts`, as a map of the
  median and the fastest sample in nanoseconds per call, and the number of
  calls in a sample."
  [f x {:keys [warmup-ms sample-ms samples]}]
  (let [estimate (warm-up f x warmup-ms)
        n        (max 1 (long (/ (* sample-ms 1e6) estimate)))
        times    (repeatedly samples #(/ (calls f x n) n))]
    {:median (double (median times))
     :min    (double (apply min times))
     :calls  n}))

;; Each round measures every benchmark once, so that a burst of other work
;; on the machine slows at most one round of a benchmark
(defn fastest-rounds
  "The result of each of the `benchmarks` in the round where its median
  time was lowest, of the :rounds of `timing`. Each result is what
  `measure` gives for a benchmark and the number of the round."
  [measure benchmarks {:keys [rounds] :as timing}]
  (let [results (vec (for [round (range 1 (inc rounds))]
                       (mapv #(measure % round) benchmarks)))]
    (apply mapv
           (fn [& round-results]
             (apply min-key (comp :median :time) round-results))
           results)))

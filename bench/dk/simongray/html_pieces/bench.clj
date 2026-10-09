(ns dk.simongray.html-pieces.bench
  "Benchmarks of html-pieces against comparable libraries: the time and the
  memory per call on the JVM, the time per call in Node, and the size of a
  browser bundle.

  Run them with clojure -X:bench after npm install. The report is printed
  as Markdown, and kept in target/bench with the results as EDN and the
  tables of doc/benchmarks.md in benchmarks.md."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dk.simongray.html-pieces.bench.areas :as areas]
            [dk.simongray.html-pieces.bench.bundle :as bundle]
            [dk.simongray.html-pieces.bench.harness :as harness]
            [dk.simongray.html-pieces.bench.jvm :as jvm]
            [dk.simongray.html-pieces.bench.report :as report])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.time LocalDate]))

(defn options
  "The `opts` with every benchmark, area or fixture for the keys that they
  leave out, the size of each fixture, and the timing that they ask for."
  [{:keys [only fixtures timing] :as opts}]
  {:only     (set (or only #{:jvm :node :bundle}))
   :areas    (set (or (:areas opts) (map :id areas/areas)))
   :fixtures (vec (for [{:keys [id] :as fixture} areas/fixtures
                        :when (or (nil? fixtures) (some #{id} fixtures))]
                    (assoc fixture
                           :bytes (.length (io/file (areas/fixture-path id))))))
   :timing   (cond
               (map? timing)     timing
               (= :quick timing) harness/quick-opts
               :else             harness/default-opts)})

(defn output
  "The output of the command `args`, trimmed, or nil when it fails."
  [& args]
  (try
    (let [{:keys [exit out]} (apply shell/sh args)]
      (when (zero? exit)
        (str/trim out)))
    (catch Exception _)))

(defn environment
  "The date, the machine and the versions that the benchmarks run on."
  []
  {:date    (str (LocalDate/now))
   :os      (str/join " " (map #(System/getProperty %)
                               ["os.name" "os.version" "os.arch"]))
   :cpu     (or (output "sysctl" "-n" "machdep.cpu.brand_string")
                (some->> (output "cat" "/proc/cpuinfo")
                         (re-find #"model name\s*:\s*(.*)")
                         (second)))
   :jvm     (str (System/getProperty "java.vm.name") " "
                 (System/getProperty "java.runtime.version"))
   :clojure (clojure-version)
   :node    (output "node" "--version")})

(defn node-results
  "The results of the Node benchmarks by `opts`, from a Node process that
  runs the built script."
  [opts]
  (let [process (-> (ProcessBuilder.
                     ["node" (:output-to bundle/node-build)
                      (pr-str (select-keys opts [:areas :fixtures :timing]))])
                    (.redirectError ProcessBuilder$Redirect/INHERIT)
                    (.start))
        out     (slurp (.getInputStream process))]
    (when-not (zero? (.waitFor process))
      (throw (ex-info "The Node benchmarks failed" {})))
    (edn/read-string out)))

(defn run
  "Run the benchmarks by `opts`, print the report, and keep it in
  target/bench with the results and the tables of doc/benchmarks.md. The
  options are:

  - :only, the benchmarks to run, of :jvm, :node and :bundle
  - :areas, the areas to compare, e.g. #{:parse :text}
  - :fixtures, the fixtures to give them, e.g. [\"comment\"]
  - :timing, :quick for short runs that only check that each one works

  Without :only, :areas or :fixtures, all of them are run."
  [opts]
  (let [{:keys [only] :as opts} (options opts)
        node?    (contains? only :node)
        bundles? (contains? only :bundle)
        _        (when (or node? bundles?)
                   (bundle/build! node? bundles?))
        node     (when node?
                   (node-results opts))
        jvm      (when (contains? only :jvm)
                   (jvm/run opts))
        results  {:environment (environment)
                  :fixtures    (:fixtures opts)
                  :timing      (:timing opts)
                  :results     (vec (concat jvm node))
                  :bundles     (when bundles?
                                 (vec (bundle/sizes)))}
        markdown (report/markdown results)]
    (io/make-parents "target/bench/results.edn")
    (spit "target/bench/results.edn" (with-out-str (pprint/pprint results)))
    (spit "target/bench/results.md" markdown)
    (spit "target/bench/benchmarks.md" (report/summary results))
    (println)
    (println markdown)))

(comment
  (run {:only #{:jvm} :areas #{:parse} :timing :quick})
  #_.)

(ns dk.simongray.html-pieces.bench.report
  "The results of the benchmarks as a Markdown report."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.bench.areas :as areas])
  (:import [java.util Locale]))

(defn fmt
  "The `args` formatted by the `pattern` of String/format, with a decimal
  point whatever the locale."
  [pattern & args]
  (String/format Locale/ROOT pattern (to-array args)))

(defn three-digits
  "The number `x` with three significant digits, for an `x` below 1000."
  [x]
  (cond
    (< x 10)  (fmt "%.2f" (double x))
    (< x 100) (fmt "%.1f" (double x))
    :else     (fmt "%.0f" (double x))))

(defn duration
  "The duration of `nanos` nanoseconds, in ns, µs, ms or s."
  [nanos]
  (cond
    (< nanos 1e3) (str (three-digits nanos) " ns")
    (< nanos 1e6) (str (three-digits (/ nanos 1e3)) " µs")
    (< nanos 1e9) (str (three-digits (/ nanos 1e6)) " ms")
    :else         (str (three-digits (/ nanos 1e9)) " s")))

(defn size
  "The size of `n` bytes, in B, KB or MB of 1024."
  [n]
  (cond
    (< n 1024)          (str (long n) " B")
    (< n (* 1024 1024)) (str (three-digits (/ n 1024)) " KB")
    :else               (str (three-digits (/ n 1024 1024)) " MB")))

(defn table
  "A Markdown table of the `header` and the `rows`, each a sequence of
  strings, with every column but the first two aligned right."
  [header rows]
  (let [line #(str "| " (str/join " | " %) " |")]
    (str/join "\n" (concat [(line header)
                            (line (concat [":--" ":--"]
                                          (repeat (- (count header) 2) "--:")))]
                           (map line rows)))))

(def platforms
  "The name of each platform."
  {:jvm "JVM" :node "Node"})

(defn area-table
  "A table of the `results` of one area, with a row for each library on
  each platform and a column for each of the `fixtures`, where a cell is
  the `metric` of a result as `show` gives it."
  [results fixtures metric show]
  (let [by-row (group-by (juxt :platform :lib) results)]
    (table (concat ["Library" "Platform"]
                   (for [{:keys [label bytes]} fixtures]
                     (str label " (" (size bytes) ")")))
           (for [[platform lib :as row] (distinct (map (juxt :platform :lib)
                                                       results))
                 :let [by-fixture (group-by :fixture (by-row row))]]
             (concat [lib (platforms platform)]
                     (for [{:keys [id]} fixtures]
                       (if-let [[result] (by-fixture id)]
                         (show (metric result))
                         "–")))))))

(defn area-tables
  "A section of a table for each area of the `results` that have a
  `metric`, as `show` gives it, with a column for each of the `fixtures`."
  [results fixtures metric show]
  (str/join "\n\n"
            (for [{:keys [id label]} areas/areas
                  :let  [in-area (filter #(and (= id (:area %)) (metric %))
                                         results)]
                  :when (seq in-area)]
              (str "### " label "\n\n"
                   (area-table in-area fixtures metric show)))))

(defn bundle-table
  "A table of the size of each of the `bundles`, and how much each adds to
  the baseline of the tool that built it."
  [bundles]
  (let [baseline (into {} (for [{:keys [tool id gzip]} bundles
                                :when (= "baseline" id)]
                            [tool gzip]))
        tools    {:shadow "shadow-cljs" :esbuild "esbuild"}]
    (table ["Bundle" "Built with" "Minified" "Gzipped" "Added, gzipped"]
           (for [{:keys [tool id label bytes gzip]} bundles]
             [label (tools tool) (size bytes) (size gzip)
              (if (= "baseline" id)
                "–"
                (size (- gzip (baseline tool))))]))))

(defn environment-list
  "A list of where the benchmarks ran, by `environment`."
  [{:keys [date os cpu jvm clojure node]}]
  (str/join "\n" (for [[k v] [["Date" date] ["OS" os] ["CPU" cpu]
                              ["JVM" jvm] ["Clojure" clojure] ["Node" node]]
                       :when v]
                   (str "- " k ": " v))))

(defn markdown
  "The `report` of the benchmarks as Markdown."
  [{:keys [environment fixtures timing results bundles] :as report}]
  (let [{:keys [warmup-ms samples sample-ms rounds]} timing]
    (str/join
     "\n\n"
     (cond-> ["# Benchmarks" (environment-list environment)]
       (seq results)
       (conj "## Time per call"
             (str "Each time is the median of " samples " samples of "
                  sample-ms " ms, after " warmup-ms " ms of warm-up"
                  (when (< 1 rounds)
                    (str ", in the fastest of " rounds " rounds"))
                  ". The libraries don't give the same output, e.g. the "
                  "sanitizers keep different elements, the text of jsoup "
                  "has no line breaks, and html-pieces sanitizes Hiccup "
                  "as it writes it as HTML.")
             (area-tables results fixtures (comp :median :time) duration))

       (some :bytes results)
       (conj "## Memory allocated per call"
             (str "The bytes that a call allocates on the JVM, measured "
                  "with ThreadMXBean. Node has no such measure.")
             (area-tables results fixtures :bytes size))

       (seq bundles)
       (conj "## Bundle size"
             (str "Each bundle is an app that calls the library, minified "
                  "for the browser. What it adds is measured against a "
                  "baseline app built by the same tool. The ClojureScript "
                  "baseline uses and prints the collections of cljs.core, "
                  "as most ClojureScript apps do.")
             (bundle-table bundles))))))

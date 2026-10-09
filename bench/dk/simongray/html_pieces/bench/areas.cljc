(ns dk.simongray.html-pieces.bench.areas
  "What the benchmarks compare: the areas of html-pieces, the fixtures that
  each library is given, and the input that an area makes of a fixture."
  (:require #?(:cljs ["fs" :as fs])
            [dk.simongray.html-pieces :as html]))

(def areas
  "The areas that the libraries are compared in, in the order of the
  report."
  [{:id :parse     :label "Parse to a tree"}
   {:id :sanitize  :label "Sanitise HTML"}
   {:id :text      :label "HTML to text"}
   {:id :markdown  :label "HTML to Markdown"}
   {:id :serialize :label "Hiccup to HTML"}])

(def fixtures
  "The fixtures in bench/fixtures, from the smallest to the largest."
  [{:id "comment"    :label "Comment"}
   {:id "show-notes" :label "Show notes"}
   {:id "article"    :label "Article"}])

(defn fixture-path
  "The path of the HTML of the fixture `id`."
  [id]
  (str "bench/fixtures/" id ".html"))

(defn fixture
  "The HTML of the fixture `id`."
  [id]
  #?(:clj  (slurp (fixture-path id))
     :cljs (fs/readFileSync (fixture-path id) "utf8")))

(defn input
  "The input that the benchmarks of `area` give each library, made of the
  HTML `s` of a fixture: the HTML itself, but for Hiccup to HTML, where
  it's the sanitized Hiccup in a div."
  [area s]
  (if (= :serialize area)
    (into [:div {}] (html/hiccup s))
    s))

(defn cases
  "Each of the `benchmarks` in the :areas of `opts`, once for each of its
  :fixtures, with the input that the area makes of the fixture."
  [benchmarks opts]
  (for [{:keys [area] :as benchmark} benchmarks
        :when (contains? (:areas opts) area)
        {id :id} (:fixtures opts)]
    (assoc benchmark
           :fixture id
           :input   (input area (fixture id)))))

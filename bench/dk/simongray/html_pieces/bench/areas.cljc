(ns dk.simongray.html-pieces.bench.areas
  "What the benchmarks compare: the areas of html-pieces, the fixtures that
  each library is given, and the input that an area makes of a fixture."
  (:require #?(:cljs ["fs" :as fs])
            [clojure.string :as str]
            [dk.simongray.html-pieces :as html]))

(def areas
  "The areas that the libraries are compared in, in the order of the
  report."
  [{:id :parse     :label "Parse to a tree"}
   {:id :sanitize  :label "Sanitise HTML"}
   {:id :text      :label "HTML to text"}
   {:id :markdown  :label "HTML to Markdown"}
   {:id :serialize :label "Hiccup to HTML"}
   {:id :built     :label "Built Hiccup to HTML"}])

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

(def ids-and-classes
  "The attributes that html-pieces allows, and an id and a class on every
  element, so that built Hiccup has shorthand tags."
  (into {} (for [tag html/allowed-tags]
             [tag (into #{:id :class} (html/allowed-attributes tag))])))

(defn shorthand
  "The `tag` with the `id` and the `class` of its element, e.g. :p#x.a.b."
  [tag id class]
  (keyword (str (name tag)
                (when id (str "#" id))
                (when class (str "." (str/replace class " " "."))))))

(defn built
  "The Hiccup `node` as an app builds it: with a shorthand tag, without an
  empty attribute map, and with the children of a list or a table in a
  seq, as for gives them."
  [node]
  (if (vector? node)
    (let [[tag {:keys [id class] :as attrs} & children] node
          attrs    (dissoc attrs :id :class)
          children (map built children)]
      (cond-> [(shorthand tag id class)]
        (seq attrs) (conj attrs)
        :always     (into (if (#{:ul :ol :dl :table :tbody :tr} tag)
                            [children]
                            children))))
    node))

(defn input
  "The input that the benchmarks of `area` give each library, made of the
  HTML `s` of a fixture: the HTML itself, but for Hiccup to HTML, where
  it's the sanitized Hiccup in a div, and for built Hiccup, where it's
  that Hiccup, with ids and classes, as an app builds it."
  [area s]
  (case area
    :serialize (into [:div {}] (html/hiccup s))
    :built     (into [:div]
                     (map built)
                     (html/hiccup s {:allowed-attributes ids-and-classes}))
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

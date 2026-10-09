(ns dk.simongray.html-pieces
  "Embedded pieces of HTML, such as comments, the descriptions in feeds or
  the fields of a CMS, read into Hiccup and rendered from there.

  TODO: an optional report of what parsing discarded and sanitizing
  removed, e.g. in metadata, so that a validator can tell the author of
  the HTML what a client won't show.

  TODO: in the browser, an option to parse with the browser's own
  DOMParser and read its DOM as Hiccup, as hickory does, which would keep
  the tokenizer, the tree builder and the table of character references
  out of the bundle. It would come at a cost:

  - the result could differ from the JVM's and Node's, since a browser
    follows every rule of tree construction, e.g. it carries formatting on
    past a misnested end tag, moves the content of tables, and rearranges
    the html, head and body of a whole page
  - Node has no DOM, so the tokenizer would still be needed there
  - DOMParser reads a whole document, so a fragment would be the children
    of its body, and :max-depth would have to be applied while reading the
    DOM"
  (:require [dk.simongray.html-pieces.commonmark :as commonmark]
            [dk.simongray.html-pieces.render :as render]
            [dk.simongray.html-pieces.serializer :as serializer]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]
            [dk.simongray.html-pieces.tree :as tree]
            [dk.simongray.html-pieces.url :as url]
            [dk.simongray.html-pieces.whitespace :as whitespace]))

;; whole tags only, so that e.g. an email address in angle brackets or a
;; less-than sign before a 3 stays plain text
(def ^:no-doc markup
  "The start of a comment, or a whole tag."
  (re-pattern (str "<!--"
                   "|<[a-zA-Z][a-zA-Z0-9]*(?:[\\t\\n\\f\\r ][^<>]*)?/?>"
                   "|</[a-zA-Z][a-zA-Z0-9]*[\\t\\n\\f\\r ]*>")))

(defn markup?
  "Whether the text `s` is HTML rather than plain text: whether it holds a
  comment or a whole tag."
  [s]
  (boolean (re-find markup (str s))))

(def max-depth
  "How deep elements nest at most. An element opened deeper is kept empty,
  and its content goes to its parent, so that no walk of the tree
  overflows the stack."
  128)

(def allowed-tags
  "The elements that are safe to render."
  #{:p :br :a :strong :b :em :i :u :s :strike :del :ins :mark :small :sup :sub
    :ul :ol :li :dl :dt :dd :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :q :cite :abbr
    :dfn :code :kbd :pre :hr :img :span :div :figure :figcaption
    :table :thead :tbody :tfoot :tr :td :th :caption})

(def dropped-tags
  "The elements that are removed with their content."
  #{:script :style :iframe :object :embed :form :input :button :select :option
    :textarea :noscript :svg :math :template :head :title :meta :link :base
    :frame :frameset :applet :audio :video :canvas})

(def allowed-attributes
  "The attributes that are safe to render, by element."
  {:a          #{:href :title :rel :hreflang}
   :img        #{:src :alt :title :width :height}
   :td         #{:colspan :rowspan}
   :th         #{:colspan :rowspan :scope}
   :ol         #{:start :reversed}
   :blockquote #{:cite}
   :q          #{:cite}
   :abbr       #{:title}
   :dfn        #{:title}})

;; HTML Standard, "Index", the attributes whose value is a URL, with
;; background and longdesc of older pages and xlink:href of SVG
(def url-attributes
  "The attributes whose value is a URL."
  #{:action :background :cite :data :formaction :href :itemid :longdesc
    :manifest :poster :src :xlink:href})

(def url-list-attributes
  "The attributes whose value is a list of URLs, separated by spaces, or by
  commas in a srcset."
  #{:itemtype :ping :srcset})

(def allowed-schemes
  "The schemes of the URLs that are safe to keep or show as links."
  #{"http" "https" "mailto"})

(def paragraph-tags
  "Elements set off by a blank line in text and Markdown."
  #{:p :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :pre :ul :ol :dl :table :hr :figure})

(def line-tags
  "Elements that end a line in text and Markdown."
  #{:div :li :tr :dt :dd :figcaption :address :caption :thead :tbody :tfoot
    :section :article})

(def max-quote-depth
  "How deep quotes nest in Markdown at most. A deeper quote is a plain
  paragraph, so that no line starts with hundreds of >."
  16)

(def default-options
  "The default of each option. Most are vars of the same name, and the
  others are:

  - :url-fn, a function of a URL that gives the URL to keep, or nil
  - :links?, true to put the URL of each link after it in plain text
  - :quirks?, true to read a C1 control character as windows-1252 in text
    and Markdown, a repair that the HTML Standard doesn't make"
  {:max-depth           max-depth
   :allowed-tags        allowed-tags
   :dropped-tags        dropped-tags
   :allowed-attributes  allowed-attributes
   :allowed-schemes     allowed-schemes
   :url-attributes      url-attributes
   :url-list-attributes url-list-attributes
   :url-fn              identity
   :paragraph-tags      paragraph-tags
   :line-tags           line-tags
   :max-quote-depth     max-quote-depth
   :links?              false
   :quirks?             true})

(defn- options
  "The `opts` with the defaults of the keys that they leave out or give as
  nil."
  [opts]
  (if (empty? opts)
    default-options
    (into default-options (remove (comp nil? val)) opts)))

(defn parse
  "The HTML `s` as a sequence of Hiccup nodes, nested at most as deep as the
  :max-depth of `opts`. Nothing is sanitized, so for HTML to render, use
  hiccup or sanitize.

  Unclosed elements close where a browser closes them, but formatting isn't
  carried on past a misnested end tag. The html, head and body elements of
  a whole page stay as they're written. Comments and DOCTYPEs are left out."
  ([s]
   (parse s {}))
  ([s opts]
   (tree/build (tokenizer/tokens s) (:max-depth (options opts)))))

(def ^:no-doc structural-tags
  "Elements whose text children are layout whitespace, never content."
  #{:ul :ol :dl :table :thead :tbody :tfoot :tr})

(defn- safe-attributes
  "The attributes `attrs` of the element `tag` that `opts` allows, with
  their URLs checked."
  [opts tag attrs]
  (let [{:keys [allowed-attributes url-attributes url-list-attributes url-fn]
         schemes :allowed-schemes} opts]
    (into {} (for [[k v] attrs
                   :when (contains? (get allowed-attributes tag) k)
                   :let  [v (cond
                              (url-attributes k)
                              (url/checked url-fn schemes v)

                              (url-list-attributes k)
                              (url/checked-list url-fn schemes
                                                (= :srcset k) v)

                              :else
                              v)]
                   :when (some? v)]
               [k v]))))

(defn- nodes-of
  "The nodes of `x`, which is a string, a node or nodes. A string is parsed
  with `opts` when it holds markup, or read as paragraphs when it's plain
  text, and anything else is no nodes."
  [x opts]
  (cond
    (string? x)       (if (markup? x) (parse x opts) (tree/paragraphs x))
    (tree/element? x) [x]
    (sequential? x)   x
    :else             []))

(defn hiccup
  "The HTML, plain text or Hiccup `x` as Hiccup that's safe to render, by
  these keys of `opts`:

  - elements of :allowed-tags are kept, with the attributes of
    :allowed-attributes
  - elements of :dropped-tags are removed with their content
  - other elements are replaced by their children
  - a URL is kept, as :url-fn rewrites it, when its scheme is one of
    :allowed-schemes, which leaves out relative URLs by default

  Plain text becomes paragraphs with line breaks. Text is decoded, e.g.
  &lt; is <, so use a renderer that escapes text."
  ([x]
   (hiccup x {}))
  ([x opts]
   (let [opts    (options opts)
         nodes   (nodes-of x opts)
         allowed (:allowed-tags opts)
         dropped (:dropped-tags opts)
         layout? #(and (string? %) (whitespace/blank? %))]
     (letfn [(node [x]
               (cond
                 (string? x)             [x]
                 (not (tree/element? x)) []
                 :else
                 (let [[tag attrs children] (tree/parts x)]
                   (if (dropped tag)
                     []
                     ;; lists and tables lose the whitespace of their layout
                     (let [kids (into []
                                      (comp (mapcat node)
                                            (if (structural-tags tag)
                                              (remove layout?)
                                              identity))
                                      children)
                           safe (safe-attributes opts tag attrs)]
                       (if (allowed tag)
                         [(into [tag safe] kids)]
                         kids))))))]
       (apply list (into [] (mapcat node) nodes))))))

(defn sanitize
  "The HTML, plain text or Hiccup `x` as HTML that's safe to render, by the
  `opts` of hiccup."
  ([x]
   (sanitize x {}))
  ([x opts]
   (serializer/html (hiccup x opts))))

(defn text
  "The HTML or Hiccup `x` as plain text, by `opts`. An image is its alt
  text, and a link is followed by its URL when :links? and the scheme is
  allowed. Control characters are left out, but for tabs and line breaks."
  ([x]
   (text x {}))
  ([x opts]
   (let [opts (options opts)]
     (render/walk (nodes-of x opts)
                  render/text-inline
                  render/text-element
                  opts))))

(defn markdown
  "The HTML or Hiccup `x` as Markdown, by `opts`. Text is escaped where
  Markdown would read it as markup, and a link or an image is its text
  when the scheme isn't allowed. Control characters are left out, but for
  tabs and line breaks."
  ([x]
   (markdown x {}))
  ([x opts]
   (let [opts (options opts)]
     (commonmark/fix-breaks (render/walk (nodes-of x opts)
                                         commonmark/inline
                                         commonmark/element
                                         opts)))))

#?(:clj
   (comment
     (parse "<p>Hello <b>world</b><br>Line two<ul><li>one<li>two</ul>")
     (hiccup "Plain text\n\nwith two paragraphs\nand a break")
     (sanitize "<p onclick=\"steal()\">Hi <b>there</b></p><script>x</script>")
     (text (str "<p>Hello <a href=\"https://x\">link</a></p>"
                "<ul><li>one</li><li>two</li></ul>")
           {:links? true})
     (markdown "<h2>Notes</h2><p>Hello <b>world</b></p>")
     #_.))

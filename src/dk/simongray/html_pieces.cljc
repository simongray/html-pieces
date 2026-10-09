(ns dk.simongray.html-pieces
  "Embedded pieces of HTML, such as comments, the descriptions in feeds or
  the fields of a CMS, read into Hiccup and rendered from there.

  A parse gives a sequence of nodes. An element is a vector of a keyword
  tag, a map of keyword attributes and its children, and text is a string.
  For HTML that a client can render, use the hiccup function. It parses
  and sanitizes HTML, and makes paragraphs of plain text. The text and
  markdown functions render HTML or Hiccup as text, and emit writes Hiccup
  back as HTML. Every function takes the same map of options, which
  default-options lists.

  The namespaces under this one hold how it's done: the tokenizer and the
  entities of the HTML Standard, the tree, the URLs, the renderers of text
  and Markdown, and the serializer.

  TODO: an optional report of what parse discarded and what sanitize
  removed, e.g. in metadata, so that a validator can tell the author of
  the HTML what a client won't show."
  (:require [dk.simongray.html-pieces.commonmark :as commonmark]
            [dk.simongray.html-pieces.render :as render]
            [dk.simongray.html-pieces.serializer :as serializer]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]
            [dk.simongray.html-pieces.tree :as tree]
            [dk.simongray.html-pieces.url :as url]
            [dk.simongray.html-pieces.whitespace :as whitespace]))

(def ^:no-doc markup
  "A comment or a whole tag, which markup? looks for."
  (re-pattern (str "<!--"
                   "|<[a-zA-Z][a-zA-Z0-9]*(?:[\\t\\n\\f\\r ][^<>]*)?/?>"
                   "|</[a-zA-Z][a-zA-Z0-9]*[\\t\\n\\f\\r ]*>")))

(defn markup?
  "Whether the text `s` is HTML rather than plain text: whether it holds a
  comment or a whole tag. Plain text may hold an email address in angle
  brackets, or a less-than sign before a 3, and neither is a tag."
  [s]
  (boolean (re-find markup (str s))))

(def max-depth
  "How deep the parse function nests elements, far deeper than embedded
  HTML goes in practice. An element opened deeper is kept empty, and its
  content goes to its parent, so that no walk of the tree overflows the
  stack."
  128)

(def allowed-tags
  "The elements that sanitize keeps."
  #{:p :br :a :strong :b :em :i :u :s :strike :del :ins :mark :small :sup :sub
    :ul :ol :li :dl :dt :dd :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :q :cite :abbr
    :dfn :code :kbd :pre :hr :img :span :div :figure :figcaption
    :table :thead :tbody :tfoot :tr :td :th :caption})

(def dropped-tags
  "The elements that sanitize removes with their content."
  #{:script :style :iframe :object :embed :form :input :button :select :option
    :textarea :noscript :svg :math :template :head :title :meta :link :base
    :frame :frameset :applet :audio :video :canvas})

(def allowed-attributes
  "The attributes that sanitize keeps, by element."
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
  "The attributes whose value is a URL, which sanitize checks."
  #{:action :background :cite :data :formaction :href :itemid :longdesc
    :manifest :poster :src :xlink:href})

(def url-list-attributes
  "The attributes whose value is a list of URLs, which sanitize checks one
  by one: ping and itemtype, separated by spaces, and srcset, separated by
  commas, with a descriptor after each URL."
  #{:itemtype :ping :srcset})

(def allowed-schemes
  "The schemes of the URLs that sanitize keeps, and that text and markdown
  show as links."
  #{"http" "https" "mailto"})

(def paragraph-tags
  "Elements set off by a blank line in text and Markdown."
  #{:p :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :pre :ul :ol :dl :table :hr :figure})

(def line-tags
  "Elements that end a line in text and Markdown."
  #{:div :li :tr :dt :dd :figcaption :address :caption :thead :tbody :tfoot
    :section :article})

(def max-quotes
  "How deeply markdown nests quotes. A quote deeper inside is a paragraph,
  so that no line carries a > for each of hundreds of levels."
  16)

(def default-options
  "The default of each option. Every function takes one map of options and
  reads the keys that it knows:

  - :max-depth, how deep parse nests elements
  - :allowed-tags, :dropped-tags and :allowed-attributes, the tables of
    sanitize
  - :allowed-schemes, the schemes of the URLs that sanitize keeps, and
    :url-attributes and :url-list-attributes, the attributes that hold them
  - :url-fn, a function of a URL that gives the URL to keep, or nil
  - :paragraph-tags and :line-tags, where text and markdown break lines
  - :max-quotes, how deeply markdown nests quotes
  - :links?, true for text to put the URL after each link
  - :quirks?, false to leave out a repair that the HTML Standard doesn't
    make: reading a C1 control in text as windows-1252

  Each of them but :url-fn, :links? and :quirks? is a var of the same name."
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
   :max-quotes          max-quotes
   :links?              false
   :quirks?             true})

(defn- options
  "The `opts` with the default-options of the keys that they leave out or
  give as nil."
  [opts]
  (if (empty? opts)
    default-options
    (into default-options (remove (comp nil? val)) opts)))

(defn parse
  "The HTML fragment `s` as a sequence of Hiccup nodes, nested at most as
  deep as the :max-depth of `opts`.

  The tokens are those of the HTML Standard, by
  dk.simongray.html-pieces.tokenizer, and the tree follows the rules of
  the standard that embedded HTML needs:

  - unclosed paragraphs, list items, table cells, headings and links
    close where HTML says they do
  - void elements, and the elements of SVG and MathML that close
    themselves with a slash, have no content
  - stray end tags are ignored, and </br> is a line break
  - comments, DOCTYPEs and processing instructions vanish

  Misnested inline elements close through the nearest match, where a
  browser would carry them on. Tags and attribute keys are lower-case
  keywords, an attribute without a value has the empty string, and text
  is a string with its character references decoded.

  A whole page parses too, e.g. for its link, meta and title elements, but
  its html, head and body elements stay as they're written, without the
  rules that a browser has for them.

  Hiccup renderers take a sequence as a fragment, so the result can go
  straight into a parent element."
  ([s]
   (parse s {}))
  ([s opts]
   (tree/build (tokenizer/tokens s) (:max-depth (options opts)))))

(def ^:no-doc structural-tags
  "Elements whose text children are layout whitespace, never content."
  #{:ul :ol :dl :table :thead :tbody :tfoot :tr})

(defn- safe-attributes
  "The attributes `attrs` of an element `tag` that the :allowed-attributes
  of `opts` has, with each URL as url/checked gives it by the :url-fn and
  the :allowed-schemes of `opts`, and without one that isn't allowed."
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

(defn sanitize
  "The Hiccup `nodes` that a client may render, by the rules below and the
  `opts` of default-options. In the result:

  - the elements and attributes of :allowed-tags and :allowed-attributes
    are kept
  - a URL is kept when its scheme is one of :allowed-schemes, after the
    function :url-fn has rewritten it
  - the elements of :dropped-tags are removed with their content
  - any other element is replaced by its children
  - lists and tables lose the whitespace of their layout

  The function :url-fn can e.g. make a relative URL absolute against the page
  that the HTML came from. A client would resolve it against its own page,
  so without such a function a relative URL is left out.

  Text in the result is decoded, e.g. &lt; is <, so render it with a
  renderer that escapes text, such as Replicant, Reagent,
  hiccup2.core/html or emit."
  ([nodes]
   (sanitize nodes {}))
  ([nodes opts]
   (let [opts    (options opts)
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
       (let [nodes (if (tree/element? nodes) [nodes] nodes)]
         (apply list (into [] (mapcat node) nodes)))))))

(defn hiccup
  "The Hiccup that a client can render for the HTML or plain text `s`, as a
  sequence of nodes to put inside a parent element. HTML is parsed and
  sanitized with the `opts` of parse and sanitize, and plain text becomes
  paragraphs with line breaks. Text in the result is decoded, so it needs
  a renderer that escapes text, as sanitize says."
  ([s]
   (hiccup s {}))
  ([s opts]
   (let [s (str s)]
     (if (markup? s)
       (sanitize (parse s opts) opts)
       (tree/paragraphs s)))))

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

(defn text
  "The HTML text or Hiccup `x` as plain text, by the `opts` of
  default-options. In the text:

  - paragraphs and the other elements of :paragraph-tags are separated by
    a blank line, and those of :line-tags end a line
  - list items are on their own lines, with a marker
  - a link is its text, followed by its URL in parentheses when :links?
    and the scheme of the URL is one of :allowed-schemes
  - an image is its alt text
  - a carriage return is a space, and other control characters are left
    out, but for tabs and line breaks. When :quirks?, a C1 control is the
    character of windows-1252 that it stands for."
  ([x]
   (text x {}))
  ([x opts]
   (let [opts (options opts)]
     (render/walk (nodes-of x opts)
                  render/text-inline
                  render/text-element
                  opts))))

(defn markdown
  "The HTML text or Hiccup `x` as Markdown, by the `opts` of
  default-options, which a Markdown renderer shows as a browser shows the
  HTML. In the Markdown:

  - headings, emphasis, links, images, lists, quotes, code and rules take
    their Markdown forms, and quotes nest at most :max-quotes deep
  - a line break becomes a backslash at the end of the line
  - text is escaped where Markdown would read it as markup
  - a link or an image is its text when the scheme of its URL isn't one of
    :allowed-schemes
  - paragraphs, line breaks and control characters are as text has them
  - everything else is its text"
  ([x]
   (markdown x {}))
  ([x opts]
   (let [opts (options opts)]
     (commonmark/with-breaks-fixed
      (render/walk (nodes-of x opts)
                   commonmark/inline
                   commonmark/element
                   opts)))))

(defn emit
  "The Hiccup or HTML text `x` as an HTML string, with the text escaped and
  the void elements without end tags. HTML or plain text in a string is
  first made Hiccup by hiccup with `opts`, and so sanitized.

  Hiccup is written as it's given, except for a name that HTML can't hold:
  such an attribute is left out, and such an element is replaced by its
  children."
  ([x]
   (emit x {}))
  ([x opts]
   (serializer/html (if (string? x)
                      (hiccup x opts)
                      (nodes-of x opts)))))

#?(:clj
   (comment
     (parse "<p>Hello <b>world</b><br>Line two<ul><li>one<li>two</ul>")
     (hiccup "Plain text\n\nwith two paragraphs\nand a break")
     (text (str "<p>Hello <a href=\"https://x\">link</a></p>"
                "<ul><li>one</li><li>two</li></ul>")
           {:links? true})
     (markdown "<h2>Notes</h2><p>Hello <b>world</b></p>")
     #_.))

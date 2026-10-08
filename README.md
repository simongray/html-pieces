# html-pieces

Pieces of HTML, such as podcast show notes, comments or the fields of a
CMS, read into Hiccup and rendered as text, Markdown or safe HTML. It's
written in `.cljc`, so it gives the same result on the JVM, in Node and in
the browser.


## Usage

```clojure
(require '[dk.simongray.html-pieces :as html])

(def notes
  (str "<h2>Notes</h2>"
       "<p>Hello <a href='https://example.com/' onclick='steal()'>world</a>,"
       "<br>welcome."
       "<ul><li>one<li>two</ul>"
       "<script>track()</script>"))

(html/hiccup notes)
;; => ([:h2 {} "Notes"]
;;     [:p {} "Hello " [:a {:href "https://example.com/"} "world"] ","
;;      [:br {}] "welcome."]
;;     [:ul {} [:li {} "one"] [:li {} "two"]])

(html/text notes)
;; => "Notes\n\nHello world,\nwelcome.\n\n- one\n- two"

(html/markdown notes)
;; => "## Notes\n\nHello [world](https://example.com/),\\\nwelcome.\n\n- one\n- two"

(html/emit (html/hiccup notes))
;; => "<h2>Notes</h2><p>Hello <a href=\"https://example.com/\">world</a>,<br>welcome.</p><ul><li>one</li><li>two</li></ul>"
```

`hiccup` parses and sanitizes HTML, so the script and the `onclick` are
gone, and it makes paragraphs of plain text, so you can give it any field.
A Hiccup renderer takes the result as a fragment. Its text is decoded, so
use a renderer that escapes text, such as Replicant, Reagent,
`hiccup2.core/html` or `emit`, and not the older `hiccup.core/html`.

A relative URL is left out, since a browser would resolve it against your
page rather than the one the HTML came from. To keep it, give `hiccup` a
`:url` function that makes it absolute against that page. An image loads
from its own server, which then knows who reads the notes and when, so to
keep images from loading, leave `:src` out of the allowed attributes of
`:img`.

For more control, call `parse` and `sanitize` yourself: `sanitize` takes
the same options as `hiccup`, including your own tables of allowed tags
and attributes. `text` puts the URL after each link with
`{:links? true}`, and `markdown` escapes whatever Markdown would read as
markup.


## Design

- **The HTML Standard first.** The tokenizer follows section 13.2.5 of
  the standard and passes the tokenizer tests of html5lib-tests, so text
  reads as it does in a browser.
- **Pieces, not documents.** The tree builder has only the rules that
  pieces of HTML need. What it leaves out, e.g. the repairs of tables, SVG
  and MathML, has a TODO in the namespace docstrings.
- **Safe by default.** `hiccup` keeps only the tags, attributes and URLs
  that an app can render safely. `text` and `markdown` leave out what a
  terminal or a Markdown renderer would act on.
- **The same everywhere.** One codebase runs on the JVM and in
  JavaScript, on Clojure 1.11 and Java 11 or later, with no other
  dependency than data.json while it compiles.


## Development

```bash
clojure -X:test               # the tests on the JVM
npm install                   # once, for the Node tests
clojure -M:cljs compile test  # the tests in Node
```

The tokenizer runs the tokenizer tests of
[html5lib-tests](https://github.com/html5lib/html5lib-tests) when they're
in `dev-resources/html5lib-tests/`, which Git ignores. To get the version
that the tokenizer passes:

```bash
git clone https://github.com/html5lib/html5lib-tests dev-resources/html5lib-tests
git -C dev-resources/html5lib-tests checkout c777c408b61078ea2eb4acefc2535f54dbc8b28a
```


## License

MIT. The table of character references in `resources/` is the
entities.json of WHATWG, under CC BY 4.0.

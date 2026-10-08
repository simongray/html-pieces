html-pieces
===========

This is a Clojure and ClojureScript library for working with embedded
pieces of HTML, e.g. comments, the descriptions in RSS feeds, or the
fields of a CMS.

- **Parsing.** It reads HTML with the tokenizer of the
  [HTML Standard](https://html.spec.whatwg.org/multipage/parsing.html), as
  a browser does.
- **Sanitising.** It keeps only what's safe to render, so you can show HTML
  from sources you don't trust.
- **Rendering.** It gives you the result as Hiccup, plain text, Markdown,
  or clean HTML.

The same code runs on the JVM, in Node and in the browser, and gives the
same results on all of them.

> This library was spun out of [podcast-clj](https://github.com/simongray/podcast-clj),
> a library for making podcast software, where it reads show notes and
> comments. Like podcast-clj, it was developed with assistance from
> frontier LLMs.

Getting started
---------------

Add it to the `:deps` in your `deps.edn` as a Git dependency, with the SHA
of the latest commit on `master` (requires Clojure 1.11+ & Java 11+):

```clojure
io.github.simongray/html-pieces {:git/sha "…"}
```

For ClojureScript, also set `:deps true` in your `shadow-cljs.edn`, since
shadow-cljs only reads Git dependencies from `deps.edn`.

Then try it on a piece of HTML:

```clojure
(require '[dk.simongray.html-pieces :as html])

(def piece
  (str "<h2>Hi!</h2>"
       "<p>Nice <a href='https://example.com/' onclick='steal()'>post</a>,"
       "<br>thanks."
       "<ul><li>one<li>two</ul>"
       "<script>track()</script>"))

(html/hiccup piece)
;; => ([:h2 {} "Hi!"]
;;     [:p {} "Nice " [:a {:href "https://example.com/"} "post"] ","
;;      [:br {}] "thanks."]
;;     [:ul {} [:li {} "one"] [:li {} "two"]])

(html/text piece)
;; => "Hi!\n\nNice post,\nthanks.\n\n- one\n- two"

(html/markdown piece)
;; => "## Hi!\n\nNice [post](https://example.com/),\\\nthanks.\n\n- one\n- two"

(html/emit (html/hiccup piece))
;; => "<h2>Hi!</h2><p>Nice <a href=\"https://example.com/\">post</a>,<br>thanks.</p><ul><li>one</li><li>two</li></ul>"
```

The `hiccup` function both parses and sanitises, so the `<script>` and the
`onclick` are gone, and the unclosed `<p>` and `<li>` are closed where a
browser closes them. Plain text works too: it becomes paragraphs and line breaks,
so you can give `hiccup` any field without checking what's in it first.

The result goes straight into a Hiccup renderer. Its text is decoded, e.g.
`&lt;` is `<`, so use a renderer that escapes text again, such as
Replicant, Reagent, `hiccup2.core/html` or `html/emit`. The older
`hiccup.core/html` doesn't.

**NOTE:** relative URLs are left out, since a browser would resolve them
against your page rather than the page the HTML came from. If you know
that page, pass a `:url` function that makes them absolute:

```clojure
(def page (java.net.URI. "https://example.com/blog/"))

(html/hiccup "<a href='/about'>About</a>" {:url #(str (.resolve page %))})
;; => ([:a {:href "https://example.com/about"} "About"])
```

Options
-------

Every function takes an optional map of options as its last argument.
The functions share one set of options, so you can pass the same map to
all of them, and `html/default-options` lists every option with its
default. For example, `:links?` makes `text` show the URL of each link:

```clojure
(html/text piece {:links? true})
;; => "Hi!\n\nNice post (https://example.com/),\nthanks.\n\n- one\n- two"
```

To change what `hiccup` keeps, pass your own tables. An image loads from
its own server, which can then see who views it, so to keep images from
loading, take `:src` out of the allowed attributes of `:img`:

```clojure
(def attributes (update html/allowed-attributes :img disj :src))

(html/hiccup "<img src='https://example.com/cat.png' alt='A cat'>"
             {:allowed-attributes attributes})
;; => ([:img {:alt "A cat"}])
```

Principles
----------

- **Standards first.** The tokenizer follows the HTML Standard and passes
  the tokenizer tests of html5lib-tests, so HTML reads as it does in a
  browser. The code cites the sections that it implements.
- **Pieces, not documents.** The tree builder only has the rules that
  pieces of HTML need. What it leaves out, e.g. the repairs of tables, SVG
  and MathML, is noted as a TODO in the namespace docstrings.
- **Lenient, but transparent.** Broken HTML is repaired the way the
  standard says, which is the way every browser repairs it. The one repair
  that the standard doesn't make, reading C1 control characters as
  windows-1252, can be switched off with `{:quirks? false}`.
- **Convention over configuration.** Each table and limit is a public var
  with a sensible default, and an option of the same name replaces it.
- **Pure functions.** Strings and Hiccup in, strings and Hiccup out, with
  no I/O and no state.
- **Safe by default.** `hiccup` keeps only the tags, attributes and URLs
  that a page can render safely, and `text` and `markdown` leave out what
  a terminal or a Markdown renderer would act on.
- **One codebase.** The library is written in `.cljc`. Its only dependency
  is data.json, which reads the table of character references when the
  code compiles.

Development
-----------

```bash
clojure -X:test               # the tests on the JVM
npm install                   # once, for the Node tests
clojure -M:cljs compile test  # the tests in Node
```

The tokenizer runs the tokenizer tests of
[html5lib-tests](https://github.com/html5lib/html5lib-tests) when they're
in `dev-resources/html5lib-tests/`, which Git ignores. CI fetches them
there. To get the version that the tokenizer passes yourself:

```bash
git clone https://github.com/html5lib/html5lib-tests dev-resources/html5lib-tests
git -C dev-resources/html5lib-tests checkout c777c408b61078ea2eb4acefc2535f54dbc8b28a
```

License
-------

The html-pieces project is licensed under the [MIT licence](LICENSE). The
table of character references in `resources/` is the `entities.json` of
WHATWG, under CC BY 4.0.

html-pieces
===========

This is a Clojure and ClojureScript library for working with embedded
pieces of HTML, e.g. comments, the descriptions in RSS feeds, or the
fields of a CMS.

- **Parsing.** It reads HTML with the
  [HTML Standard](https://html.spec.whatwg.org/multipage/parsing.html)'s
  tokenizer, as a browser does.
- **Sanitising.** It keeps only what's safe to render, so you can show HTML
  from sources you don't trust.
- **Rendering.** It gives you the result as Hiccup, plain text, Markdown,
  or safe HTML.

The same code runs on the JVM, in Node and in the browser, with the same
results on each.

> This library was spun out of [podcast-clj](https://github.com/simongray/podcast-clj),
> a library for making podcast software, which uses it to read show notes
> and comments. Like podcast-clj, it was developed with assistance from
> frontier LLMs.

Getting started
---------------

It requires Clojure 1.11+ and Java 11+. Add it to the `:deps` in your
`deps.edn` as a Git dependency, with the SHA of the latest commit on
`master`:

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

(html/sanitize piece)
;; => "<h2>Hi!</h2><p>Nice <a href=\"https://example.com/\">post</a>,<br>thanks.</p><ul><li>one</li><li>two</li></ul>"
```

Both `hiccup` and `sanitize` parse HTML and keep only what's safe, so the
`<script>` and the `onclick` are gone, and the unclosed `<p>` and `<li>`
are closed where a browser closes them. Plain text works too: it becomes
paragraphs and line breaks, so you can give them any field without
checking what's in it first. They also take Hiccup that you've built or
changed yourself.

The result of `hiccup` goes straight into a Hiccup renderer. Its text is
decoded, e.g. `&lt;` becomes `<`, so use a renderer that escapes text
again, such as Replicant, Reagent or `hiccup2.core/html`. The older
`hiccup.core/html` doesn't escape text.

**NOTE:** Relative URLs are left out, since a browser would resolve them
against your page rather than the page the HTML came from. If you know
that page, pass a `:url-fn` that makes them absolute:

```clojure
(def page (java.net.URI. "https://example.com/blog/"))

(html/hiccup "<a href='/about'>About</a>" {:url-fn #(str (.resolve page %))})
;; => ([:a {:href "https://example.com/about"} "About"])
```

Options
-------

Every function but `markup?` takes an optional map of options as its last
argument. The functions share one set of options, so you can pass the
same map to all of them. The var `html/default-options` lists every
option with its default. For example, `:links?` makes `text` show the URL
of each link:

```clojure
(html/text piece {:links? true})
;; => "Hi!\n\nNice post (https://example.com/),\nthanks.\n\n- one\n- two"
```

To change what `hiccup` keeps, pass your own tables. An image loads from
its own server, which can see who views it. To keep images from loading,
take `:src` out of the allowed attributes of `:img`:

```clojure
(def attributes (update html/allowed-attributes :img disj :src))

(html/hiccup "<img src='https://example.com/cat.png' alt='A cat'>"
             {:allowed-attributes attributes})
;; => ([:img {:alt "A cat"}])
```

Principles
----------

- **Standards first.** The tokenizer follows the HTML Standard and passes
  the tokenizer tests of html5lib-tests, so HTML is read as a browser
  reads it. The code cites the sections that it implements.
- **Pieces, not documents.** The tree builder only has the rules that
  pieces of HTML need. What it leaves out, e.g. the repairs of tables, or
  SVG and MathML, is noted as a TODO in the namespace docstrings.
- **Lenient, but transparent.** Broken HTML is repaired the way the
  standard says, which is the way every browser repairs it. The one repair
  that the standard doesn't make, reading C1 control characters as
  windows-1252, can be switched off with `{:quirks? false}`.
- **Convention over configuration.** Each table and limit is a public var
  with a sensible default, and an option of the same name replaces it.
- **Pure functions.** Strings and Hiccup in, strings and Hiccup out, with
  no I/O and no state.
- **Safe by default.** `hiccup` and `sanitize` keep only the tags,
  attributes and URLs that a page can render safely. Both `text` and
  `markdown` leave out the control characters that a terminal would obey,
  and `markdown` also escapes what a Markdown renderer would read as
  markup.
- **One codebase.** The library is written in `.cljc`. Its only dependency
  is data.json, which reads the table of character references when the
  code compiles.

Performance
-----------

The benchmarks compare html-pieces with libraries that do the same jobs,
on the JVM and in Node. The libraries don't give the same output, e.g.
the sanitizers keep different elements and jsoup's text has no line
breaks, so take the numbers as a rough guide. A bundle's size is what the
library adds to a minified browser app, gzipped. For a ClojureScript
library, that's an app which already uses the collections of cljs.core,
as most do.

| Time per call          | html-pieces | Others                                        |
| :--------------------- | ----------: | :-------------------------------------------- |
| Parse to a tree, JVM   |      270 µs | hickory 380 µs, clj-tagsoup 1.43 ms           |
| Parse to a tree, Node  |      384 µs | parse5 325 µs, htmlparser2 132 µs             |
| Sanitise HTML, JVM     |      414 µs | jsoup 324 µs, OWASP 182 µs                    |
| Sanitise HTML, Node    |      761 µs | sanitize-html 395 µs                          |
| HTML to text, JVM      |      458 µs | jsoup 128 µs                                  |
| HTML to text, Node     |      770 µs | html-to-text 526 µs                           |
| HTML to Markdown, JVM  |      542 µs | flexmark 1.09 ms, copy_down 9.41 ms           |
| HTML to Markdown, Node |      973 µs | turndown 1.37 ms                              |
| Hiccup to HTML, JVM    |      132 µs | hiccup 64.3 µs, huff 739 µs, replicant 121 µs |
| Hiccup to HTML, Node   |      334 µs | replicant 554 µs                              |

| Allocated per call    | html-pieces | Others                                        |
| :-------------------- | ----------: | :-------------------------------------------- |
| Parse to a tree, JVM  |      546 KB | hickory 1.15 MB, clj-tagsoup 4.33 MB          |
| Sanitise HTML, JVM    |      946 KB | jsoup 351 KB, OWASP 192 KB                    |
| HTML to text, JVM     |     1.19 MB | jsoup 162 KB                                  |
| HTML to Markdown, JVM |     1.30 MB | flexmark 691 KB, copy_down 6.87 MB            |
| Hiccup to HTML, JVM   |      387 KB | hiccup 413 KB, huff 1.69 MB, replicant 439 KB |

| Bundle                      | Added, gzipped |
| :-------------------------- | -------------: |
| html-pieces, parse          |        15.2 KB |
| html-pieces, hiccup         |        17.5 KB |
| html-pieces, markdown       |        19.2 KB |
| html-pieces, every function |        20.6 KB |
| hickory                     |        2.92 KB |
| parse5                      |        40.3 KB |
| DOMPurify                   |        11.3 KB |
| sanitize-html               |        56.8 KB |
| turndown                    |        3.94 KB |

The numbers are for the 11.1 KB article in `bench/fixtures`. They were
measured on 2026-10-09, on an Apple M1 with OpenJDK 64-Bit Server VM
24.0.1, Clojure 1.12.5 and Node v24.1.0.

Development
-----------

```bash
clojure -X:test               # the tests on the JVM
npm install                   # once, for the Node tests and benchmarks
clojure -M:cljs compile test  # the tests in Node
clojure -X:bench              # the benchmarks against other libraries
```

The benchmarks take a few minutes. They print a report, which is kept in
`target/bench/` with the tables of the Performance section in
`readme.md`. To run some of them, pass e.g. `:only '#{:jvm}'`,
`:areas '#{:parse}'` or `:timing :quick`.

The tests run the tokenizer tests of
[html5lib-tests](https://github.com/html5lib/html5lib-tests) when they're
in `dev-resources/html5lib-tests/`, which Git ignores. CI clones them into
that folder. To do the same yourself, at the version that the tokenizer
passes:

```bash
git clone https://github.com/html5lib/html5lib-tests dev-resources/html5lib-tests
git -C dev-resources/html5lib-tests checkout c777c408b61078ea2eb4acefc2535f54dbc8b28a
```

License
-------

The html-pieces project is licensed under the [MIT licence](LICENSE). The
table of character references in `resources/` is WHATWG's
`entities.json`, under CC BY 4.0.

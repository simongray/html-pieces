html-pieces
===========

[![Clojars Project](https://img.shields.io/clojars/v/dk.simongray/html-pieces.svg)](https://clojars.org/dk.simongray/html-pieces)

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

It requires Clojure 1.11+ and Java 11+. Add it from
[Clojars](https://clojars.org/dk.simongray/html-pieces) to the `:deps` in
your `deps.edn`:

```clojure
dk.simongray/html-pieces {:mvn/version "0.7.0"}
```

For unreleased changes, depend on the latest commit on `master` instead.
In shadow-cljs, a Git dependency only works through `deps.edn`, so set
`:deps true` in your `shadow-cljs.edn` too.

The main functions
------------------

Here's what each of them makes of a piece of HTML:

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
checking what's in it first. Text with a tag in it is read as HTML,
though, so for a field that's always plain text, e.g. a summary that can
hold a `<`, pass `{:plain-text? true}`:

```clojure
(html/hiccup "Use <b> for bold." {:plain-text? true})
;; => ([:p {} "Use <b> for bold."])
```

For a line of text in a list or a notification, `excerpt` gives the text
cut at the end of a word:

```clojure
(html/excerpt piece 20)
;; => "Hi! Nice post,…"
```

Hiccup
------

The result of `hiccup` goes straight into a Hiccup renderer. Its text is
decoded, e.g. `&lt;` becomes `<`, so use a renderer that escapes text
again, such as Replicant, Reagent or `hiccup2.core/html` (the older
`hiccup.core/html` doesn't escape text).

Every function but `markup?` also takes Hiccup that you've built or
changed yourself, and reads it as every Hiccup renderer does:

- a seq among the children, e.g. from `for` or `map`, is spliced in
- nil is skipped
- a number is text
- a tag such as `:p#intro.lead` is a `p` with an `:id` and a `:class`

Conventions that only some renderers have, such as event handlers, style
maps and a class as a collection, aren't supported. When an option allows
an attribute that holds one, `serialize` and `sanitize` throw with the
`:type` `:dk.simongray.html-pieces/unwritable-attribute`.

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

### Relative URLs

Relative URLs are left out, since a browser would resolve them against
your page rather than the page the HTML came from. If you know that page,
pass its URL as `:base-url`:

```clojure
(html/hiccup "<a href='/about'>About</a>"
             {:base-url "https://example.com/blog/"})
;; => ([:a {:href "https://example.com/about"} "About"])
```

In a whole page, a `<base href>` counts too, as it does in a browser, so
the URLs are resolved against the URL that `base-url` gives.

For a URL outside HTML, e.g. the link of a feed, `resolve` in the
`dk.simongray.html-pieces.url` namespace does the same. Both resolve URLs
with [whatwg-url](https://github.com/simongray/whatwg-url), the way
browsers do.

### What's kept

To change what `hiccup` keeps, pass your own tables. An image loads from
its own server, which can see who views it. To keep images from loading,
take `:src` out of the allowed attributes of `:img`:

```clojure
(def attributes (update html/allowed-attributes :img disj :src))

(html/hiccup "<img src='https://example.com/cat.png' alt='A cat'>"
             {:allowed-attributes attributes})
;; => ([:img {:alt "A cat"}])
```

A link keeps its `rel`, but not the link types that would speak for your
page, which `html/dropped-rels` lists: `me`, `author`, `license`, and the
endpoints of Webmention, IndieAuth and the like. RelMeAuth and Mastodon
take a `rel="me"` anywhere on a page as another profile of its owner, so
a comment with one could sign its writer in as you:

```clojure
(html/hiccup "<a rel='me nofollow' href='https://mastodon.example/@mallory'>Hi</a>")
;; => ([:a {:rel "nofollow" :href "https://mastodon.example/@mallory"} "Hi"])
```

For HTML that you trust, e.g. the links of your own profile, pass
`:dropped-rels #{}`.

To change an element rather than keep it or leave it out, pass an
`:element-fn`. It's given each element that's kept, once its attributes
and children are safe, and returns the Hiccup to put in its place. For
example, to keep the headings of a reply from another site out of your
page's outline, and to mark its links as user content:

```clojure
(defn reply-element
  "The `element` of a reply as your page shows it."
  [[tag attrs & children :as element]]
  (case tag
    (:h1 :h2 :h3) (into [:p {}] children)
    :a            (assoc-in element [1 :rel] "nofollow ugc")
    element))

(html/sanitize "<h1>Hi</h1><p>See <a href='https://example.com/'>this</a>"
               {:element-fn reply-element})
;; => "<p>Hi</p><p>See <a href=\"https://example.com/\" rel=\"nofollow ugc\">this</a></p>"
```

What it returns is sanitized again, so it can't bring in an element or
an attribute that isn't allowed. To keep it as it is, e.g. with a class
that isn't allowed, also pass `:trusted-element-fn? true`. For the text
or Markdown of the result, give `text` or `markdown` what `hiccup`
returns.

Parsing and serializing
-----------------------

To read HTML without sanitizing it, e.g. to find the microformats of a
page, use `parse`, and to write it, use `serialize`. Neither is safe for
HTML that you don't trust, so render what `hiccup` or `sanitize` gives
instead.

In the tree that `parse` gives, each element is a vector of the tag, an
attribute map and the children, and text is a string:

```clojure
(html/parse "<p class=x>See <a href=/about>this</a><script>track()</script>")
;; => ([:p {:class "x"} "See " [:a {:href "/about"} "this"]
;;      [:script {} "track()"]])
```

For Hiccup that you've built, `parse` gives the same shape, so that you
can walk any Hiccup the same way.

Given HTML or Hiccup, `serialize` writes it as a browser writes the
`innerHTML` of an element:

```clojure
(html/serialize "<P class=x>Hi<br/>there<script>track()</script>")
;; => "<p class=\"x\">Hi<br>there<script>track()</script></p>"
```

A tag in HTML can hold a dot or a #, e.g. that of the custom element
`<x-a.b>`, but in Hiccup they're shorthand for a class or an id. When you
give the Hiccup of `parse` to `serialize` or another function, pass
`{:shorthand? false}`, so that each tag is read as it's written:

```clojure
(html/serialize (html/parse "<x-a.b>t</x-a.b>") {:shorthand? false})
;; => "<x-a.b>t</x-a.b>"
```

The links of a page
-------------------

To find what a whole page links to, e.g. the endpoint of Webmention, use
`links`. It resolves each href against the page's base URL, which
`base-url` gives, so pass the page's URL as `:base-url`:

```clojure
(html/links "<link rel=webmention href=/mention><p>Hi <a href=/about>me</a>"
            {:base-url "https://example.com/post" :rel "webmention"})
;; => ([:link {:rel "webmention" :href "https://example.com/mention"}])
```

The `:rel` option takes the links of one link type, in any case. The
`:link-tags` option gives the elements that count, `a`, `area` and `link`
by default. Pass `:body? false` to take only the links in the head of the
page, and its base from there too, e.g. for IndieAuth, since a comment on
the page can add a link or a base element to its body.

For more of a page, `head` gives the elements in its head, also of a page
without a head tag, and `elements` gives all its elements in document
order.

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
  markup. Only `parse` and `serialize` keep HTML as it is.
- **One codebase.** The library is written in `.cljc`. Its dependencies
  are whatwg-url, which resolves URLs, and data.json, which reads the
  table of character references when the namespace loads on the JVM, and
  when it compiles for ClojureScript.

Performance
-----------

See the [benchmarks](doc/benchmarks.md) for how html-pieces compares with
libraries that do the same jobs, on the JVM, in Node and in the browser.

Development
-----------

```bash
clojure -X:test                       # the tests on the JVM
npm install                           # once, for ClojureScript and benchmarks
clojure -M:cljs compile test          # the tests in Node
clojure -M:cljs compile browser-test  # the tests in a browser
node test/browser-run.mjs             # run them in a headless Chrome
clojure -X:bench                      # the benchmarks against other libraries
```

The benchmarks take a few minutes. They print a report, which is kept in
`target/bench/` with the tables of `doc/benchmarks.md` in
`benchmarks.md`. To run some of them, pass e.g. `:only '#{:jvm}'`,
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

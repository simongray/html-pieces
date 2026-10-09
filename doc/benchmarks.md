Benchmarks
==========

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

To run the benchmarks yourself, see Development in the
[README](../README.md#development).

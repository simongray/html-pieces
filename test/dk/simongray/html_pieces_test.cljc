(ns dk.simongray.html-pieces-test
  "Embedded HTML as Hiccup, sanitized, and rendered as text and Markdown."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.html-pieces.serializer :as serializer]))

(deftest parsing-the-way-a-browser-does
  (testing "elements, attributes and entities"
    (is (= [[:p {:class "x"} "Hello " [:b {} "world"] " & co"]]
           (html/parse "<P class=x>Hello <b>world</b> &amp; co</p>")))
    (is (= [[:a {:href "https://x/?a=1&b=2" :title "It's" :download ""} "link"]]
           (html/parse "<a href=\"https://x/?a=1&amp;b=2\" title='It&#39;s' download>link</a>"))
        "an attribute without a value has the empty string, as in the DOM"))
  (testing "headings and links that aren't closed"
    (is (= [[:h3 {} "One\n"] [:h3 {} "Two"]]
           (html/parse "<h3>One\n<h3>Two")))
    (is (= [[:a {:href "x"} "one" [:br {}]] [:a {:href "y"} "two"]]
           (html/parse "<a href=x>one<br><a href=y>two</a>")))
    (is (= [[:p {} "a" [:br {}] "b"]]
           (html/parse "<p>a</br>b</p>"))
        "an end tag br is a line break"))
  (testing "void elements, with or without a slash"
    (is (= [[:p {} "one" [:br {}] "two"] [:hr {}] [:img {:src "i.png" :alt ""}]]
           (html/parse "<p>one<br/>two<hr><img src=i.png alt=\"\">"))))
  (testing "unclosed paragraphs and list items close where HTML says"
    (is (= [[:p {} "a"] [:p {} "b"] [:div {} "c"]]
           (html/parse "<p>a<p>b<div>c")))
    (is (= [[:p {} "a"] [:search {} "b"] [:p {} "c"] [:dialog {} "d"]]
           (html/parse "<p>a<search>b</search><p>c<dialog>d</dialog>"))
        "the two elements that came to the list of HTML last")
    (is (= [[:ul {} [:li {} "one"] [:li {} "two" [:ul {} [:li {} "nested"]]] [:li {} "three"]]]
           (html/parse "<ul><li>one<li>two<ul><li>nested</ul><li>three</ul>"))))
  (testing "misnested inline elements close through the nearest match"
    (is (= [[:b {} [:i {} "x"]] " y"]
           (html/parse "<b><i>x</b> y</i>"))))
  (testing "comments, declarations and word-processor tags"
    (is (= [[:p {} "text"]]
           (html/parse "<!DOCTYPE html><!-- note --><p>text</p>")))
    (is (= [[:o:p {} "word"]] (html/parse "<o:p>word</o:p>"))))
  (testing "scripts keep their content as text"
    (is (= [[:script {} "if (a < b) { x() }"]]
           (html/parse "<script>if (a < b) { x() }</script>")))
    (is (= [[:p {} "a"] [:script {} "for(i=0;i<n;i++){}"] [:p {} "b"]]
           (html/parse "<p>a</p><SCRIPT>for(i=0;i<n;i++){}</script><p>b</p>"))
        "however much of it looks like a tag, up to its end tag in any case"))
  (testing "a > in a quoted value, and a slash at the end of one without quotes"
    (is (= [[:img {:alt "a > b" :src "x.png"}] [:a {:href "https://x/"} "link"]]
           (html/parse "<img alt=\"a > b\" src=\"x.png\"><a href=https://x/>link</a>"))))
  (testing "text alone is text"
    (is (= ["just words"] (html/parse "just words")))
    (is (= [] (html/parse ""))))
  (testing "a whole page, whose html, head and body stay as written"
    (is (= [[:html {}
             [:head {} [:title {} "Show"] [:link {:rel "alternate" :href "feed.xml"}]]
             [:body {} [:p {} "x"]]]]
           (html/parse (str "<!DOCTYPE html><html><head><title>Show</title>"
                            "<link rel=alternate href=feed.xml></head>"
                            "<body><p>x</body></html>"))))
    (is (= [[:title {} "Show"] [:link {:rel "alternate" :href "feed.xml"}] [:p {} "x"]]
           (html/parse "<title>Show</title><link rel=alternate href=feed.xml><p>x"))))
  (testing "the result is a sequence, which every Hiccup renderer takes as a fragment"
    (is (seq? (html/parse "<p>a</p><p>b</p>")))
    (is (seq? (html/hiccup "plain")))))

(deftest sanitizing
  (let [dirty (str "<p onclick=\"evil()\" style=\"x\">Hi <a href=\"javascript:alert(1)\" target=\"_blank\">bad</a> "
                   "<a href=\"https://ok/\" rel=\"nofollow\" class=\"c\">good</a> <a href=\"/relative\">rel</a></p> "
                   "<script>alert(1)</script><iframe src=\"x\"></iframe><font color=red>plain</font> "
                   "<ul>\n  <li>one</li>\n  <li>two</li>\n</ul><img src=\"data:image/png;base64,AAAA\" alt=\"pic\">")]
    (is (= [[:p {} "Hi " [:a {} "bad"] " " [:a {:href "https://ok/" :rel "nofollow"} "good"] " " [:a {} "rel"]]
            " " "plain" " "
            [:ul {} [:li {} "one"] [:li {} "two"]]
            [:img {:alt "pic"}]]
           (html/hiccup dirty)))
    (is (= [[:dfn {:title "term"} "x"]]
           (html/hiccup "<dfn title=\"term\" class=\"c\">x</dfn>")))
    (testing "the tables of the caller in the place of the vars"
      (is (= [[:p {} "Hi " [:a {:class "c"} "bad"] " " "plain"]]
             (html/hiccup "<p id=\"x\">Hi <a href=\"javascript:alert(1)\" class=\"c\">bad</a> <font>plain</font><em>gone</em></p>"
                          {:allowed-attributes (update html/allowed-attributes :a conj :class)
                           :dropped-tags       (conj html/dropped-tags :em)})))
      (is (= ["Hi " [:b {} "there"]]
             (html/hiccup "<p>Hi <b title=\"t\">there</b></p>" {:allowed-tags #{:b}})))))
  (testing "javascript: behind the tabs, line breaks and control characters a browser drops"
    (doseq [href ["java&#9;script:alert(1)" "java&#10;script:alert(1)" "java&#x0D;script:alert(1)"
                  "&#1;javascript:alert(1)" " javascript:alert(1)" "java\tscript:alert(1)"]]
      (is (= [[:a {} "x"]] (html/hiccup (str "<a href=\"" href "\">x</a>"))) href)))
  (testing "a relative URL, which a client would resolve against its own page"
    (is (= [[:a {} "rel"] [:img {:alt "pic"}]]
           (html/hiccup "<a href=\"/rel\">rel</a><img src=\"/logout\" alt=\"pic\">")))
    (is (= [[:a {:href "https://show.example/rel"} "rel"]]
           (html/hiccup "<a href=\"/rel\">rel</a>"
                        {:url-fn #(str "https://show.example" %)})))
    (is (= [[:a {} "x"]]
           (html/hiccup "<a href=\"https://ok/\">x</a>"
                        {:url-fn (constantly "javascript:alert(1)")}))
        "what :url-fn gives is checked too")
    (is (= [[:a {} "x"] [:a {} "y"]]
           (html/hiccup (str "<a href=\"javascript&colon;alert(1)\">x</a>"
                             "<a href=\"java&Tab;script:alert(1)\">y</a>")))
        "a name of HTML5 that isn't decoded hides no scheme, since the URL is relative"))
  (testing "the URLs of the attributes that a caller allows, and only as strings"
    (let [allowed (assoc html/allowed-attributes :img #{:srcset} :a #{:ping})]
      (is (= [[:img {:srcset "https://x/a.png 1x, https://x/b.png 2x"}]
              [:img {}]
              [:a {:ping "https://x/p https://y/q"} "x"]]
             (html/hiccup (str "<img srcset=\"https://x/a.png 1x, https://x/b.png 2x\">"
                               "<img srcset=\"https://x/a.png 1x, javascript:alert(1) 2x\">"
                               "<a ping=\"https://x/p https://y/q\">x</a>")
                          {:allowed-attributes allowed}))))
    (is (= [[:a {} "y"] [:a {} "z"]]
           (html/hiccup [[:a {:href (keyword "javascript:alert(1)")} "y"]
                         [:a {:href ["javascript:alert(1)"]} "z"]]))))
  (testing "Hiccup of any shape, and options of nil"
    (is (= [[:p {}] "y"] (html/hiccup [[:p []] [nil] [1 "x"] "y"])))
    (is (= [[:p {} "x"]] (html/hiccup [[:p "x"]] {:allowed-tags nil})))
    (is (= [[:p {} "x"]] (html/hiccup [:p "x"])) "an element alone")))

(deftest hostile-markup
  (testing "nesting stops at max-depth, so that no walk of the tree overflows the stack"
    (let [deep  (apply str (repeat 10000 "<span>"))
          depth (fn depth [x] (if (vector? x) (inc (reduce max 0 (map depth (drop 2 x)))) 0))]
      (is (>= (inc html/max-depth) (reduce max 0 (map depth (html/parse deep)))))
      (is (= 1 (count (html/hiccup deep))))
      (is (= "" (html/text deep)))
      (is (string? (html/sanitize deep)))))
  (testing "end tags that close nothing are ignored at once, however deep the tree"
    (is (= "ab" (html/text (str (apply str (repeat 500 "<span>")) "a" (apply str (repeat 50000 "</b>")) "b")))))
  (testing "a long run of stray angle brackets is one text, read in linear time"
    (let [text (apply str (repeat 50000 "1<"))]
      (is (= [[:p {} text]] (html/parse (str "<p>" text))))))
  (testing "a long run of blanks in a pre is kept, and tidied in linear time"
    (let [blanks (apply str (repeat 100000 " "))]
      (is (= (str "a" blanks "b") (html/text (str "<pre>a" blanks "b</pre>"))))))
  (testing "a start tag with thousands of quoted values costs no stack"
    (let [tag (str "<a " (apply str (map #(str "a" % "=\"b>\" ") (range 20000))) ">x</a>")]
      (is (= [20000 "b>"] ((juxt count :a19999) (second (first (html/parse tag))))))
      (is (= "x" (html/text tag)))
      (is (= [[:a {} "x"]] (html/hiccup tag)))))
  (testing "markup without an ampersand, comments and scripts, read in linear time"
    (let [times #(apply str (repeat %1 %2))]
      (is (= 100000 (count (html/parse (times 100000 "<b>x</b>")))))
      (is (= [] (html/parse (str (times 100000 "<!--x-->") (times 100000 "<!--x--!>")))))
      (is (= 40000 (count (html/parse (times 40000 "<script><!--</script>")))))
      (is (= 100000 (count (html/parse (times 100000 "<title>x</title>")))))))
  (testing "nested quotes and pres, rendered in linear time"
    (let [quotes (str (apply str (repeat 128 "<blockquote>")) (apply str (repeat 1000 "<p>x")))
          pres   (str (apply str (repeat 128 "<pre>")) (apply str (repeat 20000 "a\n")))]
      (is (str/starts-with? (html/markdown quotes) (str (apply str (repeat 16 "> ")) "x\n")))
      (is (str/starts-with? (html/text pres) "a\na\n"))
      (is (str/starts-with? (html/markdown pres) "```\na\na\n")))))

(deftest telling-html-from-plain-text
  (is (every? html/markup? ["<p>Hi</p>" "a <!-- note -->" "a<br/>b" "x</b>"]))
  (is (not-any? html/markup? ["Write to <show@example.com>" "I <3 it" "a < b > c" "" nil])))

(deftest hiccup-for-a-client
  (testing "markup is parsed and sanitized"
    (is (= [[:p {} "Hi " [:b {} "there"]]] (html/hiccup "<p>Hi <b>there</b><script>x</script></p>"))))
  (testing "with options"
    (is (= [[:a {:href "https://x/" :class "mention"} "@ann"]]
           (html/hiccup "<a href=\"https://x/\" class=\"mention\" id=\"m\">@ann</a>"
                        {:allowed-attributes (update html/allowed-attributes :a conj :class)}))))
  (testing "plain text becomes paragraphs with breaks"
    (is (= [[:p {} "Line one" [:br {}] "line two"] [:p {} "Second & last"]]
           (html/hiccup "Line one\nline two\n\n\nSecond &amp; last\n")))
    (is (= [[:p {} "Write to <show@example.com> <3"]] (html/hiccup "Write to <show@example.com> <3"))
        "an address in angle brackets is no tag"))
  (is (= [] (html/hiccup nil)))
  (is (= [] (html/hiccup "   "))))

(deftest rendering-text
  (let [notes "<h2>Show  notes</h2><p>Hello <a href=\"https://x/\">world</a>,<br>welcome.</p>
               <ul><li>one</li><li>two <b>bold</b></li></ul><ol><li>first</li><li>second</li></ol><p>Bye <img src=\"a.png\" alt=\"[art]\"></p>"]
    (is (= "Show notes\n\nHello world,\nwelcome.\n\n- one\n- two bold\n\n1. first\n2. second\n\nBye [art]"
           (html/text notes)))
    (is (str/includes? (html/text notes {:links? true}) "world (https://x/)"))
    (is (= "a\n\nb" (html/text [[:p {} "a"] [:p {} "b"]])))
    (is (= "keep   this" (html/text "<pre>keep   this</pre>")))
    (is (= "plain text" (html/text "plain text")))
    (is (= "Hello\n\nWorld" (html/text "<p>Hello</p><script>for(i=0;i<n;i++){}</script><style>p{}</style><p>World</p>"))
        "what a browser does not show is no text")
    (is (= "  a\n    b" (html/text "<pre>  a\n    b\n</pre>")) "a pre keeps its indentation")
    (is (= "Write to <show@example.com>\nsoon" (html/text "Write to <show@example.com>\nsoon")) "plain text stays plain")
    (is (= "x" (html/text [:p "x"])) "hiccup without an attribute map")
    (let [nbsp (char 160)]
      (is (= (str "a" nbsp nbsp " b" nbsp) (html/text "<p>a&nbsp;&nbsp; b&nbsp;</p>"))
          "a no-break space isn't whitespace that HTML collapses or trims, on either platform")))
  (testing "no control characters that a terminal would obey"
    (is (= "a[2Jb\n\nsafe evil" (html/text "<p>a&#27;[2Jb</p><pre>safe&#13;evil</pre>")))
    (is (= (str "don" (char 0x2019) "t") (html/text (str "<p>don" (char 0x92) "t" (char 0x81) "</p>")))
        "a C1 control is the character of windows-1252 that it stands for, if any")
    (is (= "x w (https://x/y) a b"
           (html/text "<a href=\"javascript:alert(1)\">x</a> <a href=\"https://x/&#10;y\">w</a> <img alt=\"a&#10;&#10;b\">"
                      {:links? true}))
        "nor in a URL or an alt text, and no URL that isn't http, https or mailto"))
  (is (= "" (html/text [[:p []] [nil] [1 "x"]])) "Hiccup of any shape"))

(deftest rendering-markdown
  (is (= "## Notes\n\nHello **world** and *you*, [link](https://x/)\\\nnext line.\n\n- one\n- two\n\n1. first\n2. second\n\n> quoted\\\n> lines\n\n---\n\n```\ncode block\n```\n\n![art](https://x/a.png) `x`"
         (html/markdown "<h2>Notes</h2><p>Hello <strong>world</strong> and <em>you</em>, <a href=\"https://x/\">link</a><br>next line.</p>
                         <ul><li>one</li><li>two</li></ul><ol><li>first</li><li>second</li></ol>
                         <blockquote>quoted<br>lines</blockquote><hr><pre>code block</pre><p><img src=\"https://x/a.png\" alt=\"art\"> <code>x</code></p>")))
  (is (= "```\n  indented\n    more\n```\n\nafter" (html/markdown "<pre>  indented\n    more\n</pre><p>after</p>"))
      "a pre keeps its indentation")
  (testing "a link or an image whose URL isn't http, https or mailto is its text"
    (is (= "x ![y](https://x/a.png) y z"
           (html/markdown (str "<a href=\"javascript:alert(1)\">x</a> <img src=\"https://x/a.png\" alt=\"y\"> "
                               "<img src=\"data:text/html,x\" alt=\"y\"> <a href=\"/rel\">z</a>")))))
  (testing "text, which Markdown shows as it is"
    (is (= "\\<img src=x onerror=alert(1)\\> and \\[y\\](javascript:alert(2)) 5\\*3\\*2 \\&colon; a\\_b \\~x\\~ \\|"
           (html/markdown "<p>&lt;img src=x onerror=alert(1)&gt; and [y](javascript:alert(2)) 5*3*2 &amp;colon; a_b ~x~ |</p>")))
    (is (= "\\# not a heading\n\n1\\. not a list\n\n\\- nor this\n\n- \\# x"
           (html/markdown "<p># not a heading</p><p>1. not a list</p><p>- nor this</p><ul><li># x</li></ul>"))))
  (testing "the start of a block from two texts"
    (is (= "1\\. x\n\n- 1\\) y\n\n> 1\\. z\n\na\\\n12\\. w, years1. **3**. v"
           (html/markdown "<p>1<span>. x</span></p><ul><li>1<span>) y</span></li></ul><blockquote>1<span>. z</span></blockquote><p>a<br>1<a href=\"javascript:x\">2</a>. w, years<sup>1</sup>. <b>3</b>. v</p>")))
    (is (= "1\\. x" (html/markdown [:p "1" ". x"])) "Hiccup")
    (is (= "1\\. x" (html/markdown "1<span>. x</span>")) "at the top level")
    (is (= "\\# x\n\n1\\. y"
           (html/markdown "<p><img alt=\"# x\"></p><p><img src=\"javascript:x\" alt=\"1\">. y</p>"))
        "an alt text as text"))
  (testing "a URL, a link text or an alt text that can't end its link"
    (is (= (str "[t](https://ok/\\)[z]\\(javascript:alert\\(3\\)) "
                "[x\\](javascript:alert(1)) \\[y](https://a.example/) "
                "![\\](javascript:alert(1))\\[click](https://ok/a.png) "
                "[e](https://x/?a=1\\&colon;b%20c)")
           (html/markdown (str "<a href=\"https://ok/)[z](javascript:alert(3)\">t</a> "
                               "<a href=\"https://a.example/\">x](javascript:alert(1)) [y</a> "
                               "<img src=\"https://ok/a.png\" alt=\"](javascript:alert(1))[click\"> "
                               "<a href=\"https://x/?a=1&amp;colon;b c\">e</a>")))))
  (testing "line breaks and spaces at the ends of blocks and emphasis"
    (is (= "**Credits:**\\\nHosts **spaced** end\n\na\n\nb"
           (html/markdown "<p><strong>Credits:<br></strong>Hosts<b> spaced </b>end<br></p><p>a<br><br>b</p>"))))
  (testing "a heading on one line, with each line break as a space"
    (is (= "## a b\n\n## c d e"
           (html/markdown "<h2>a<br>b</h2><h2>c <br><br> d<p>e</p></h2>"))))
  (testing "code with backticks, and emphasis without text"
    (is (= "``` `` ```x\n\n````\na\n```\n# not code\n````\n\n```\nx y\n```"
           (html/markdown "<b></b><code></code><p><code>``</code>x</p><pre>a\n```\n# not code</pre><pre><b>x</b> <code>y</code></pre>"))))
  (is (= "" (html/markdown [[:p []] [nil]])) "Hiccup of any shape"))

(deftest options
  (testing "an option in the place of each default, one map for every function"
    (is (= [[:b {} [:i {} [:u {}] "x"]]] (html/parse "<b><i><u>x" {:max-depth 2})))
    (is (= [[:a {:href "ftp://x/"} "x"] [:a {} "y"]]
           (html/hiccup "<a href=\"ftp://x/\">x</a><a href=\"https://y/\">y</a>"
                        {:allowed-schemes #{"ftp"}})))
    (is (= [[:img {:alt "a"}] [:img {:data-src "https://x/a.png"}]]
           (html/hiccup "<img data-src=\"javascript:x\" alt=a><img data-src=\"https://x/a.png\">"
                        {:allowed-attributes {:img #{:data-src :alt}}
                         :url-attributes     #{:data-src}})))
    (is (= "a\nb" (html/text "<p>a</p><p>b</p>" {:paragraph-tags #{} :line-tags #{:p}})))
    (is (= "x (ftp://x/)" (html/text "<a href=\"ftp://x/\">x</a>" {:links? true :allowed-schemes #{"ftp"}})))
    (is (= "> x" (html/markdown "<blockquote><blockquote>x</blockquote></blockquote>" {:max-quote-depth 1})))
    (is (= "<a href=\"https://s.example/x\">x</a>" (html/sanitize "<a href=\"/x\">x</a>" {:url-fn #(str "https://s.example" %)}))))
  (testing "the repair that the HTML Standard doesn't make, switched off"
    (is (= "dont" (html/text (str "<p>don" (char 0x92) "t</p>") {:quirks? false}))))
  (testing "an option of nil is the default"
    (is (= "a\n\nb" (html/text "<p>a</p><p>b</p>" {:paragraph-tags nil})))
    (is (= html/max-depth (:max-depth html/default-options)))))

(deftest sanitizing-html
  (is (= "<p>a</p>" (html/sanitize "<p onclick=\"x()\">a</p><script>s()</script>")))
  (is (= "<p>a &lt; b</p>" (html/sanitize "a < b")) "plain text as a paragraph")
  (is (= "<p>a &amp; b<br><a href=\"https://x/?a=1&amp;b=2\">l</a></p><ol reversed></ol>"
         (html/sanitize [[:p {:class "c"} "a & b" [:br {}] [:a {:href "https://x/?a=1&b=2"} "l"]]
                         [:input {:disabled true}]
                         [:ol {:reversed true}]]))
      "Hiccup, with only what's safe, and its text and values escaped")
  (is (= "<p>x</p>" (html/sanitize [:p "x"])) "an element alone, without an attribute map")
  (testing "safe Hiccup survives a trip through HTML"
    (let [nodes (html/hiccup "<p>Hi <a href=\"https://x/\">there</a><br>you</p><ul><li>one</li></ul>")]
      (is (= nodes (html/parse (html/sanitize nodes)))))))

(deftest hiccup-as-an-app-builds-it
  (testing "a seq among the children is spliced in, e.g. of a for"
    (let [x [:ul (for [s ["one" "two"]] [:li s])]]
      (is (= [[:ul {} [:li {} "one"] [:li {} "two"]]] (html/hiccup x)))
      (is (= "<ul><li>one</li><li>two</li></ul>" (html/sanitize x)))
      (is (= "- one\n- two" (html/text x)))
      (is (= "- one\n- two" (html/markdown x))))
    (is (= "a *b*" (html/markdown [:p (list "a " (map identity [[:em "b"]]))])) "a seq in a seq")
    (is (= "- one\n- two" (html/text [:ul [:li (list "one")] [:li (list "two")]]))
        "the text of a tight list item, as nextjournal.markdown gives it")
    (is (= "1. a\n2. b\n3. c" (html/text [:ol [:li "a"] (list [:li "b"] [:li "c"])]))
        "in an ordered list, which counts on through a seq"))
  (testing "nil is skipped"
    (let [x [:p nil "a" nil [:b nil "b"]]]
      (is (= [[:p {} "a" [:b {} "b"]]] (html/hiccup x)))
      (is (= "<p>a<b>b</b></p>" (html/sanitize x)))
      (is (= "ab" (html/text x)))
      (is (= "a**b**" (html/markdown x)))))
  (testing "a number is text"
    (let [x [:p "Count: " 42]]
      (is (= [[:p {} "Count: " "42"]] (html/hiccup x)))
      (is (= "<p>Count: 42</p>" (html/sanitize x)))
      (is (= "Count: 42" (html/text x)))
      (is (= "Count: 42" (html/markdown x))))
    (is (= ["42"] (html/hiccup 42)) "alone"))
  (testing "a shorthand tag is its tag, with an id and a class"
    (let [x [:div#main.intro [:h2.title "Hi"] [:p.lead.big "a"] [:p#end "b"]]]
      (is (= [[:div {} [:h2 {} "Hi"] [:p {} "a"] [:p {} "b"]]] (html/hiccup x)))
      (is (= "<div><h2>Hi</h2><p>a</p><p>b</p></div>" (html/sanitize x)))
      (is (= "Hi\n\na\n\nb" (html/text x)))
      (is (= "## Hi\n\na\n\nb" (html/markdown x))))
    (let [allowed {:div #{:id :class} :p #{:id :class}}]
      (is (= [[:div {:id "main" :class "intro"} [:p {:class "lead big"} "a"]]]
             (html/hiccup [:div#main.intro [:p.lead.big "a"]] {:allowed-attributes allowed}))
          "which are kept when they're allowed")
      (is (= "<p class=\"x y z\" id=\"a\"></p>"
             (html/sanitize [:p#a.x.y {:class "z"}] {:allowed-attributes allowed}))
          "and an attribute map adds its class")
      (is (= "<p id=\"b\"></p>"
             (html/sanitize [:p#a {:id "b"}] {:allowed-attributes allowed}))
          "and its id wins, as in most Hiccup renderers")))
  (testing "the conventions together"
    (let [x [:div.intro [:p.lead "Count: " 42] [:ul (for [s ["one" "two"]] [:li s])] nil]]
      (is (= "<div><p>Count: 42</p><ul><li>one</li><li>two</li></ul></div>" (html/sanitize x)))
      (is (= "Count: 42\n\n- one\n- two" (html/text x)))))
  (testing "but HTML is read as HTML, where a tag can hold a dot or a #"
    (is (= "<b>a</b>x" (html/sanitize "<b>a</b><p.lead>x</p.lead>")))
    (is (= "ax" (html/text "<b>a</b><p.lead>x</p.lead>")))))

(defn reply-element
  "The `element` of a reply from another site as a page shows it."
  [[tag attrs & children :as element]]
  (case tag
    :h1  (into [:p {}] children)
    :h2  (into [:strong {}] children)
    :h3  (into [:h5 attrs] children)
    :img (:alt attrs)
    :a   (assoc-in element [1 :rel] "nofollow ugc")
    element))

(deftest replacing-elements
  (let [opts {:element-fn reply-element}]
    (testing "headings as paragraphs, strong text or smaller headings"
      (is (= [[:p {} "Big"] [:strong {} "Less " [:em {} "so"]] [:h5 {} "Least"]]
             (html/hiccup "<h1>Big</h1><h2>Less <em>so</em></h2><h3>Least</h3>" opts))))
    (testing "an image as its alt text"
      (is (= "<p>A cat, </p>"
             (html/sanitize "<p><img src=\"https://x/cat.png\" alt=\"A cat\">, <img src=\"https://x/y.png\"></p>" opts))))
    (testing "links that a search engine shouldn't follow, whatever their own rel"
      (is (= "<a href=\"https://x/\" rel=\"nofollow ugc\">x</a> <a rel=\"nofollow ugc\">y</a>"
             (html/sanitize "<a href=\"https://x/\" rel=\"me\">x</a> <a href=\"javascript:y()\">y</a>" opts)))))
  (testing "an element given back as it is, which is kept"
    (is (= [[:p {} "x"]] (html/hiccup "<p onclick=\"y()\">x</p>" {:element-fn identity}))))
  (testing "nil in place of an element, or a seq of its children"
    (is (= "<p>a</p>" (html/sanitize "<p>a<b>b</b></p>"
                                      {:element-fn (fn [[tag :as e]] (when (not= :b tag) e))}))
        "which leaves out the element with its content")
    (is (= "<p>ab</p>" (html/sanitize "<p>a<b>b</b></p>"
                                      {:element-fn (fn [[tag _ & children :as e]] (if (= :b tag) children e))}))))
  (testing "what's in place of an element is sanitized again, but :url-fn is applied once"
    (let [linked (fn [[tag _ & children :as e]]
                   (if (= :p tag)
                     [:a {:href (first children) :onclick "x()"} [:script "y"] (first children)]
                     e))]
      (is (= "<a>javascript:alert(1)</a>" (html/sanitize "<p>javascript:alert(1)</p>" {:element-fn linked})))
      (is (= "<a href=\"javascript:alert(1)\" onclick=\"x()\"><script>y</script>javascript:alert(1)</a>"
             (html/sanitize "<p>javascript:alert(1)</p>" {:element-fn linked :trusted-element-fn? true}))
          "unless it's trusted"))
    (is (= [[:h4 {} "x"]] (html/hiccup "<h1>x</h1>" {:element-fn #(assoc % 0 :h4)}))
        "and it isn't given to the function again")
    (is (= [[:a {:href "https://s.example/x" :title "t"} "x"]]
           (html/hiccup "<a href=\"/x\">x</a>" {:url-fn     #(str "https://s.example" %)
                                                :element-fn #(assoc-in % [1 :title] "t")}))))
  (testing "built Hiccup in place of an element"
    (let [para (fn [[tag _ & children :as e]] (if (= :div tag) [:p.x (seq children) 1] e))]
      (is (= "<p>a<b>b</b>1</p>" (html/sanitize "<div>a<b>b</b></div>" {:element-fn para})))
      (is (= "<p class=\"x\">a<b>b</b>1</p>"
             (html/sanitize "<div>a<b>b</b></div>" {:element-fn para :trusted-element-fn? true})))))
  (testing "no function, the default"
    (is (= [[:h1 {} "x"]] (html/hiccup "<h1>x</h1>" {:element-fn nil})))))

(deftest writing-html
  (is (= "<p class=\"c\">a &amp; b<br><a href=\"https://x/?a=1&amp;b=2\">l</a></p><input disabled>"
         (serializer/html [[:p {:class "c"} "a & b" [:br {}] [:a {:href "https://x/?a=1&b=2"} "l"]]
                           [:input {:disabled true}]])))
  (is (= "<p title=\"t\">x</p>c<input><p></p>"
         (serializer/html [[:p {(keyword "onmouseover=alert(1) x") "y" :title "t"} "x"]
                           [(keyword "a b") "c"]
                           [:input {:disabled false :value nil}]
                           [:p []] [nil] 1]))
      "names that HTML can't hold, false and nil, and Hiccup of any shape"))

(ns isaac.util.edn-spec
  (:require
    [clojure.string :as str]
    [isaac.util.edn :as sut]
    [speclj.core :refer :all]))

(describe "isaac.util.edn/pretty (isaac-524u)"
  (it "Example 1: wide map -> block, sorted, justified, braces on own lines"
    (should= (str "{\n"
                  "  :api      \"responses\"\n"
                  "  :api-key  \"${XAI_API_KEY}\"\n"
                  "  :auth     \"api-key\"\n"
                  "  :base-url \"https://api.x.ai/v1\"\n"
                  "}")
             (sut/pretty {:api      "responses"
                          :base-url "https://api.x.ai/v1"
                          :auth     "api-key"
                          :api-key  "${XAI_API_KEY}"}
                         80)))

  (it "Example 2: small map -> inline"
    (should= "{:model :grok-4-5 :provider :grok}"
             (sut/pretty {:model :grok-4-5 :provider :grok} 80)))

  (it "Example 3: nesting with contextual fit and per-map-local justification"
    (should= (str "{\n"
                  "  :compaction {:head 0.15 :threshold 0.5}\n"
                  "  :crew       :scrapper\n"
                  "  :tools      {\n"
                  "    :allow       [:read :write :edit]\n"
                  "    :directories [:cwd]\n"
                  "  }\n"
                  "}")
             (sut/pretty {:crew       :scrapper
                          :compaction {:head 0.15 :threshold 0.5}
                          :tools      {:allow       [:read :write :edit]
                                       :directories [:cwd]}}
                         80)))

  (it "Example 4: vector over budget -> block, original order, no justify"
    (should= (str "{\n"
                  "  :directories [\n"
                  "    \"/Users/zane/agents\"\n"
                  "    \"/Users/zane/Projects\"\n"
                  "    \"/Users/zane/.isaac/config\"\n"
                  "    \"/Users/zane/work\"\n"
                  "  ]\n"
                  "}")
             (sut/pretty {:directories ["/Users/zane/agents"
                                        "/Users/zane/Projects"
                                        "/Users/zane/.isaac/config"
                                        "/Users/zane/work"]}
                         80)))

  (it "empty collections render inline"
    (should= "{}" (sut/pretty {} 80))
    (should= "[]" (sut/pretty [] 80)))

  (it "scalars render via pr-str"
    (should= "nil" (sut/pretty nil 80))
    (should= ":kw" (sut/pretty :kw 80))
    (should= "42" (sut/pretty 42 80)))

  (it "width clamp is between 40 and 80"
    (should= 40 (sut/clamp-width 10))
    (should= 80 (sut/clamp-width 200))
    (should= 60 (sut/clamp-width 60)))

  (it "contextual fit: collection that fits at top level may break at deep indent"
    (let [nested {:a 1 :b 2 :c 3}
          ;; tiny remaining budget forces break even for a small map
          out (sut/pretty nested 40 38)]
      (should (str/includes? out "\n")))))

(ns isaac.foundation.template-spec
  (:require
    [isaac.foundation.template :as sut]
    [speclj.core :refer :all]))

(describe "foundation template"
  (it "renders keyword and string bindings and dotted paths"
    (should= "Ada / Grace / 0 / false"
             (sut/render "{{name}} / {{data.keeper.name}} / {{count}} / {{enabled}}"
                         {"name" "Ada" :data {"keeper" {:name "Grace"}} :count 0 :enabled false})))

  (it "renders hyphenated names and prefers keyword keys over string keys"
    (should= "isaac-8379 / keyword"
             (sut/render "{{bean-id}} / {{data.name}}"
                         {:bean-id "isaac-8379" :data {:name "keyword" "name" "string"}})))

  (it "leaves non-placeholder braces intact"
    (should= "{{space here}} / {{a/b}}"
             (sut/render "{{space here}} / {{a/b}}" {})))

  (it "keeps absent placeholders by default"
    (should= "{{absent}} {{data.missing}}"
             (sut/render "{{absent}} {{data.missing}}" {:data {}})))

  (it "supports empty and marker policies for missing bindings"
    (should= " / " (sut/render "{{absent}} / {{data.missing}}" {} {:on-missing :empty}))
    (should= "(missing)" (sut/render "{{absent}}" {} {:on-missing :marker})))

  (it "renders nil templates and bound nil as empty text"
    (should= "" (sut/render nil {}))
    (should= "" (sut/render "{{value}}" {:value nil})))

  (it "renders strings throughout nested values without changing non-strings"
    (should= {:list ["Hi Ada" {:value '("Ada" 3 false)}]
              :flag true :number 0 :nil nil}
             (sut/render-all {:list ["Hi {{name}}" {:value '("{{name}}" 3 false)}]
                              :flag true :number 0 :nil nil}
                             {:name "Ada"} {:on-missing :empty})))

  (it "collects placeholder names in nested values"
    (should= #{"name" "data.keeper-name" "other"}
             (sut/placeholders {:text "{{name}} {{data.keeper-name}} {{name}}"
                                :nested ["{{other}}" 7]})))
  )

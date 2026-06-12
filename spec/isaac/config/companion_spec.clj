(ns isaac.config.companion-spec
  (:require
    [isaac.config.companion :as sut]
    [speclj.core :refer :all]))

(describe "config companion"

  (it "prefers inline text over companion text"
    (should= {:value             "Inline prompt."
              :inline?           true
              :companion-exists? true
              :companion-empty?  false}
             (select-keys (sut/resolve-text {:inline  "Inline prompt."
                                             :load-fn (fn [] {:exists? true :text "Markdown prompt."})})
                          [:value :inline? :companion-exists? :companion-empty?])))

  (it "uses companion text when inline text is absent"
    (should= "Markdown prompt."
             (:value (sut/resolve-text {:inline  nil
                                        :load-fn (fn [] {:exists? true :text "Markdown prompt."})}))))

  (it "marks an empty companion file distinctly from a missing file"
    (should= {:value nil :companion-exists? true :companion-empty? true}
             (select-keys (sut/resolve-text {:inline  nil
                                             :load-fn (fn [] {:exists? true :text ""})})
                          [:value :companion-exists? :companion-empty?]))))

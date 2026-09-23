(ns isaac.config.schema-compose-spec
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.config.marigold :as config-marigold]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as sut]
    [isaac.schema.lexicon :as lexicon]
    [speclj.core :refer :all]))

(def ^:private cross-fragment-index
  "Two-module fixture mirroring the monolith providers+server error-aggregation case."
  {:mod.a {:manifest {:isaac.config/schema
                      {:alpha {:schema {:name       "alpha table"
                                        :type       :map
                                        :key-spec   {:type :string}
                                        :value-spec {:type   :map
                                                     :schema {:headers {:type       :map
                                                                        :key-spec   {:type :string}
                                                                        :value-spec {:type :string}}}}}}}}}
   :mod.b {:manifest {:isaac.config/schema
                      {:beta {:schema {:name   :beta
                                       :type   :map
                                       :schema {:port {:type :int}}}}}}}})

(def ^:private template-index
  "A :defaults block declared as entity templates over a crew table and the
   non-table :frequencies kind."
  {:mod.x {:manifest {:isaac.config/schema
                      {:crew        {:schema {:name       "crew table"
                                              :type       :map
                                              :key-spec   {:type :string}
                                              :value-spec {:name   :crew
                                                           :type   :map
                                                           :schema {:id     {:type :id}
                                                                    :effort {:type :int}
                                                                    :model  {:type        :id
                                                                             :required?   true
                                                                             :validations [:present?]}}}}}
                       :frequencies {:schema {:name   :frequencies
                                              :type   :map
                                              :schema {:crew      {:type :string}
                                                       :reach     {:type :keyword}
                                                       :with-crew {:type :string}}}}
                       :defaults    {:schema {:name   :defaults
                                              :type   :map
                                              :schema {:crew        {:type            :map
                                                                     :entity-template {:kind :crew :except [:id]}}
                                                       :frequencies {:type            :map
                                                                     :entity-template {:kind     :frequencies
                                                                                       :except   [:with-crew]
                                                                                       :override {:crew {:type :id :required? true}}}}}}}}}}})

;; Deferred (isaac-p5v2 #3): "root conforms a complete config" over every REAL shipped
;; fragment belongs as a smoke test in the top-level isaac app once it exists.

(describe "config schema-compose"

  (describe "inline :schema contributions"

    (it "composes an inline schema map into the root schema"
      (let [index {:mod.x {:manifest {:isaac.config/schema
                                      {:tunes {:schema {:name        :tunes
                                                        :type        :map
                                                        :description "Shanty config"
                                                        :schema      {:volume {:type :int}}}}}}}}
            root  (sut/compose-root-schema index)]
        (should= {:type :int} (get-in root [:schema :tunes :schema :volume]))))

    (it "rejects a contribution whose :schema is not a map"
      (should-throw clojure.lang.ExceptionInfo
                    (sut/compose-root-schema
                      {:mod.x {:manifest {:isaac.config/schema
                                          {:tunes {:schema 42}}}}})))

    (it "meta-validates inline schemas and names the offender"
      (let [e (should-throw clojure.lang.ExceptionInfo
                            (sut/compose-root-schema
                              {:mod.x {:manifest {:isaac.config/schema
                                                  {:tunes {:schema {:type   :map
                                                                    :schema {:volume {:type :warble}}}}}}}}))]
        (should-contain ":warble" (.getMessage e)))))

  (describe "descriptors"

    (it "derives entity metadata from inline :isaac.config/schema contributions"
      (let [index       {:mod.x {:manifest {:isaac.config/schema
                                            {:berths {:schema       {:type :map :key-spec {:type :keyword} :value-spec {:type :map}}
                                                      :entity-dir   "berths"
                                                      :frontmatter? true}
                                             :cron   {:schema     {:type :map :key-spec {:type :keyword} :value-spec {:type :map}}
                                                       :entity-dir "cron"}}}}}
            descriptors (sut/descriptors index)]
        (should= "berths" (:entity-dir (:berths descriptors)))
        (should (true? (:frontmatter? (:berths descriptors))))
        (should= "cron" (:entity-dir (:cron descriptors)))
        (should-be-nil (:frontmatter? (:cron descriptors))))))

  (describe ":defaults entity templates"

    (it "expands a template from the entity schema"
      (let [root (sut/effective-root-schema template-index)]
        (should= {:type :int} (get-in root [:schema :defaults :schema :crew :schema :effort]))))

    (it "drops the marker once expanded"
      (let [root (sut/effective-root-schema template-index)]
        (should-be-nil (get-in root [:schema :defaults :schema :crew :entity-template]))))

    (it "a template never requires a field the entity requires"
      (let [model (get-in (sut/effective-root-schema template-index)
                          [:schema :defaults :schema :crew :schema :model])]
        (should-be-nil (:required? model))
        (should-be-nil (:validations model))))

    (it "omits excepted fields"
      (let [root (sut/effective-root-schema template-index)]
        (should-be-nil (get-in root [:schema :defaults :schema :crew :schema :id]))
        (should-be-nil (get-in root [:schema :defaults :schema :frequencies :schema :with-crew]))))

    (it "copies a non-table kind's own schema"
      (let [root (sut/effective-root-schema template-index)]
        (should= {:type :keyword} (get-in root [:schema :defaults :schema :frequencies :schema :reach]))))

    (it "applies declared overrides on top of the template"
      (let [crew (get-in (sut/effective-root-schema template-index)
                         [:schema :defaults :schema :frequencies :schema :crew])]
        (should= :id (:type crew))
        (should (true? (:required? crew))))))

  (describe "composed root"

    (it "each map entity is a named spec with schema or table layout"
      (let [entities (dissoc (:schema (config-marigold/test-root-schema)) :modules)]
        (doseq [[_nm spec] entities
                :when (= :map (:type spec))]
          (should (or (some? (:name spec)) (some? (:description spec))))
          (should (or (map? (:schema spec))
                      (and (map? (:key-spec spec)) (map? (:value-spec spec))))))))

    (it "rejects invalid types with per-field errors across fragments"
      (let [root   (schema-base/strip-validation-annotations
                     (sut/effective-root-schema cross-fragment-index))
            result (lexicon/conform root {:alpha {"x" {:headers 42}}
                                         :beta  {:port "not-a-number"}})]
        (should (schema/error? result))
        (should= {:alpha {"x" {:headers "can't coerce 42 to map"}}
                  :beta  {:port "can't coerce \"not-a-number\" to int"}}
                 (schema/message-map result))))))

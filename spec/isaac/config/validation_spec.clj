(ns isaac.config.validation-spec
  (:require
    [isaac.config.validation :as sut]
    [speclj.core :refer :all]))

(describe "config validation"

  (describe "data validation refs"

    (defn- errors-for [validations value]
      (sut/annotation-errors* nil [:field] {:validations validations} value))

    (it ":positive? rejects 0 with a positive-integer message"
      (should= "must be a positive integer" (:value (first (errors-for [:positive?] 0)))))

    (it ":positive? accepts 3"
      (should= [] (errors-for [:positive?] 3)))

    (it ":non-negative? rejects -1 with a non-negative-integer message"
      (should= "must be a non-negative integer" (:value (first (errors-for [:non-negative?] -1)))))

    (it ":non-negative? accepts 0"
      (should= [] (errors-for [:non-negative?] 0)))

    (it ":absolute-path? rejects a relative path"
      (should= "must be an absolute path" (:value (first (errors-for [:absolute-path?] "tmp/x")))))

    (it ":absolute-path? accepts /tmp/x"
      (should= [] (errors-for [:absolute-path?] "/tmp/x")))

    (it ":keyword-set? rejects a vector of keywords"
      (should= "must be a set of keywords" (:value (first (errors-for [:keyword-set?] [:a :b])))))

    (it ":keyword-set? accepts #{:a :b}"
      (should= [] (errors-for [:keyword-set?] #{:a :b})))

    (it ":keyword-or-string? rejects 42"
      (should= "must be a keyword or string" (:value (first (errors-for [:keyword-or-string?] 42)))))

    (it ":keyword-or-string? accepts :compact and \"compact\""
      (should= [] (errors-for [:keyword-or-string?] :compact))
      (should= [] (errors-for [:keyword-or-string?] "compact")))

    (it ":cwd-or-path? rejects a bare keyword other than :cwd or :quarters"
      (should= "must be :cwd, :quarters, or an absolute path string" (:value (first (errors-for [:cwd-or-path?] :home)))))

    (it ":cwd-or-path? rejects the retired :role token"
      (should= "must be :cwd, :quarters, or an absolute path string" (:value (first (errors-for [:cwd-or-path?] :role)))))

    (it ":cwd-or-path? accepts :cwd, :quarters, and a path string"
      (should= [] (errors-for [:cwd-or-path?] :cwd))
      (should= [] (errors-for [:cwd-or-path?] :quarters))
      (should= [] (errors-for [:cwd-or-path?] "/srv/work"))))

  (describe "parameterized validation refs"

    (it "[:retired? hint] always errors, folding the hint into the message"
      (should= "retired; use :server :auth :token"
               (:value (first (sut/annotation-errors* nil [:token]
                                                      {:validations [[:retired? "use :server :auth :token"]]}
                                                      "abc")))))

    (it "[:requires-any? ...] errors when none of the named fields is populated"
      (should= "must include at least one of :crew, :crew-tags"
               (:value (first (sut/annotation-errors* nil [:addressing]
                                                      {:validations [[:requires-any? :crew :crew-tags]]}
                                                      nil {:reach :one} :addressing)))))

    (it "[:requires-any? ...] passes when one named field has entries"
      (should= [] (sut/annotation-errors* nil [:addressing]
                                          {:validations [[:requires-any? :crew :crew-tags]]}
                                          nil {:crew ["atticus"]} :addressing))))

  (describe "present-when? under conformed values"

    (it "fires when the discriminator was coerced to a string id"
      (should= "is required when type is parlor"
               (:value (first (sut/annotation-errors* nil [:loft]
                                                      {:validations [[:present-when? :type :parlor]]}
                                                      nil {:type "parlor"} :loft))))))

  (describe "compaction-flavored refs"

    (it "[:percentage? hint] rejects 1.0, folding the hint into the message"
      (should= "must be a percentage in [0.0, 1.0); e.g. 0.8 for 80% of context-window"
               (:value (first (sut/annotation-errors* nil [:threshold]
                                                      {:validations [[:percentage? "e.g. 0.8 for 80% of context-window"]]}
                                                      1.0)))))

    (it "[:percentage? hint] accepts 0.8"
      (should= [] (sut/annotation-errors* nil [:threshold]
                                          {:validations [[:percentage? "e.g. 0.8 for 80% of context-window"]]}
                                          0.8)))

    (it "[:less-than? :head :threshold] errors when head meets threshold"
      (should= "head must be smaller than threshold"
               (:value (first (sut/annotation-errors* nil [:head-threshold]
                                                      {:validations [[:less-than? :head :threshold]]}
                                                      nil {:head 0.8 :threshold 0.8} :head-threshold)))))

    (it "[:less-than? :head :threshold] passes when either side is absent"
      (should= [] (sut/annotation-errors* nil [:head-threshold]
                                          {:validations [[:less-than? :head :threshold]]}
                                          nil {:head 0.3} :head-threshold))))

  (describe "existence-ref tagging"

    (it "tags :model-exists? errors as :reference?"
      (let [entry (first (sut/annotation-errors* nil [:model] {:validations [:model-exists?]} "ghost"))]
        (should= "references undefined model" (:value entry))
        (should= true (:reference? entry))))

    (it "does not tag a value-validator error as :reference?"
      (let [entry (first (sut/annotation-errors* nil [:tags] {:validations [:keyword-set?]} "jackalope"))]
        (should= "must be a set of keywords" (:value entry))
        (should-not (:reference? entry)))))

  (describe "absent nested maps"

    ;; A nested map spec carries two obligations that isaac-ruom's first cut
    ;; conflated. `:present?` on an inner field means "if this map is written,
    ;; this field must be in it" — it says nothing about whether the map may be
    ;; left out. `:required? true` on an inner field is what makes the map
    ;; itself unskippable, and only that may make validation descend into a map
    ;; that was never written.

    (defn- optional-map-spec []
      ;; :episodes :embedding — an optional capability (isaac-episodes)
      {:type   :map
       :schema {:api   {:type :id :validations [:present?]}
                :model {:type :string :validations [:present?]}}})

    (defn- demanding-map-spec []
      ;; :defaults :frequencies — omitting it is omitting the default crew
      {:type   :map
       :schema {:crew  {:type :id :required? true :validations [:present?]}
                :model {:type :id}}})

    (it "an absent map whose inner fields are only :present? reports nothing"
      (should= [] (sut/annotation-errors* nil ["episodes" "embedding"] (optional-map-spec) nil)))

    (it "an absent map with a :required? true inner field still names that field"
      (let [entries (sut/annotation-errors* nil ["defaults" "frequencies"] (demanding-map-spec) nil)]
        (should= ["defaults.frequencies.crew"] (map :key entries))
        (should= ["is required"] (map :value entries))))

    (it "a written map still requires its :present? inner fields"
      (should= ["episodes.embedding.api" "episodes.embedding.model"]
               (map :key (sut/annotation-errors* nil ["episodes" "embedding"] (optional-map-spec) {}))))

    (it "a populated map reports nothing"
      (should= [] (sut/annotation-errors* nil ["episodes" "embedding"] (optional-map-spec)
                                          {:api :marigold :model "longwave-1"})))

    (it "a parent map may omit an optional child but not a demanding one"
      (let [parent {:type   :map
                    :schema {:embedding   (optional-map-spec)
                             :frequencies (demanding-map-spec)}}]
        (should= ["defaults.frequencies.crew"]
                 (map :key (sut/annotation-errors* nil ["defaults"] parent {})))))

    (it "leaves an absent principal rotation overlap alone"
      ;; :http :auth :principals <id> :previous — inner :hash is :present? only
      (let [principal {:type   :map
                       :schema {:hash     {:type :string :validations [:present?]}
                                :previous {:type   :map
                                           :schema {:hash {:type :string :validations [:present?]}}}}}]
        (should= [] (sut/annotation-errors* nil ["http" "auth" "principals" "skipper"] principal
                                            {:hash "argon2id$marigold"})))))

  (describe "validate-manifest-config"

    (it "reports unknown keys as warnings"
      (should= [{:key "tools.foo.unknown" :value "unknown key"}]
               (:warnings (sut/validate-manifest-config "tools.foo" {:known "x" :unknown "y"}
                                                        {:known {:type :string}}))))))
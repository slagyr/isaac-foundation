(ns isaac.module.manifest-spec
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.manifest :as sut]
    [speclj.core :refer :all])
  (:import (java.io File)))

(def pigeon-manifest
  ;; Phase 8 (isaac-qqgv): comm contributions live at :isaac.server/comm,
  ;; not the deleted :comm extension kind.
  {:id                :isaac.comm/pigeon
   :version           "0.1.0"
   :bootstrap         'isaac.comm.pigeon/bootstrap
   :description       "Carrier pigeon comm"
   :isaac.server/comm {:pigeon {:factory 'isaac.comm.pigeon/make
                                :schema  {:loft      {:type :string :validations [:present?]}
                                          :max-bytes {:type :int :coercions [[:default 140]]}}}}})

(def api-manifest
  ;; Phase 7 (isaac-ho18): llm/api contributions live at
  ;; :isaac.server/llm-api.
  {:id                   :isaac.api.tin-can
   :version              "0.1.0"
   :isaac.server/llm-api {:tin-can {:factory 'isaac.api.tin-can/make}}})

(def slash-echo-manifest
  ;; Phase 7 (isaac-ho18): slash-command contributions live at
  ;; :isaac.server/slash-commands.
  {:id                          :isaac.slash.echo
   :version                     "0.1.0"
   :isaac.server/slash-commands {:echo {:factory 'isaac.slash.echo/echo-command
                                        :schema  {:command-name {:type :string}}}}})

(def tool-manifest
  ;; Phase 6 (isaac-w7o5): tools contribute to the :isaac.server/tools
  ;; berth, not a hardcoded top-level :tools kind.
  {:id                 :isaac.tool.doodad
   :version            "0.1.0"
   :isaac.server/tools {:doodad {:factory 'isaac.tool.doodad/doodad-tool
                                 :schema  {:api-key {:type :string}}}}})

(def provider-only-manifest
  ;; Phase 7 (isaac-ho18): provider templates contribute to the
  ;; :isaac.server/provider-template berth, not a hardcoded :provider
  ;; extension kind.
  {:id                              :isaac.providers.kombucha
   :version                         "0.1.0"
   :isaac.server/provider-template  {:kombucha {:template {:api "chat-completions"}}}})

(def route-manifest
  ;; Phase 5 of the berth epic (isaac-8v1n): routes are now berth
  ;; contributions, not a top-level extension kind. Per-entry shape
  ;; validation moved to the berth's :manifest :schema.
  {:id                 :isaac.routes.bibelot
   :version            "0.1.0"
   :isaac.server/route [{:method :get :path "/status"  :handler 'isaac.server.status/handle}
                        {:method :*   :path "/hooks/*" :handler 'isaac.hooks/handler}]})

(def cli-manifest
  {:id      :isaac.cli.greeter
   :version "0.1.0"
   :cli     {:greet {:factory     'isaac.cli.greeter/make-command
                     :description "Print a greeting"}}})

(def berth-manifest
  {:id      :marigold.bridge
   :version "1.0.0"
   :factory 'marigold.bridge/create-module})

(def builtin-manifest
  {:id       :isaac.server
   :version  "1.0.0"
   :builtin? true})

(describe "module manifest"

  (describe "manifest-schema"

    (it "is a named :map spec"
      (should= :map (:type sut/manifest-schema))
      (should= :module/manifest (:name sut/manifest-schema))
      (should (map? (:schema sut/manifest-schema)))))

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (describe "read-manifest"

    (with tmp-file (File/createTempFile "manifest" ".edn"))
    (after (.delete @tmp-file))

    (it "parses a v2 manifest with :comm and :factory"
      (spit (.getPath @tmp-file) (pr-str pigeon-manifest))
      (should= pigeon-manifest (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "parses a manifest that extends :llm/api"
      (spit (.getPath @tmp-file) (pr-str api-manifest))
      (should= api-manifest (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "parses a tools manifest with :factory and :schema"
      (spit (.getPath @tmp-file) (pr-str tool-manifest))
      (should= tool-manifest (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "parses a provider-only manifest without :bootstrap"
      (spit (.getPath @tmp-file) (pr-str provider-only-manifest))
      (should= provider-only-manifest (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "parses a manifest with declarative routes"
      (spit (.getPath @tmp-file) (pr-str route-manifest))
      (should= route-manifest (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "parses a manifest with :cli extensions"
      (spit (.getPath @tmp-file) (pr-str cli-manifest))
      (should= cli-manifest (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "parses a manifest with a top-level :factory symbol"
      (spit (.getPath @tmp-file) (pr-str berth-manifest))
      (should= berth-manifest (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "preserves the builtin classpath discovery flag"
      (spit (.getPath @tmp-file) (pr-str builtin-manifest))
      (should= builtin-manifest (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "reads string paths from an explicit fs"
      (let [mem  (fs/mem-fs)
            path "/tmp/manifest.edn"]
        (fs/spit mem path (pr-str pigeon-manifest))
        (should= pigeon-manifest (sut/read-manifest path mem))))

    ;; Phase 4 of the berth epic: :cli is now a berth, not a hardcoded
    ;; extension kind. Per-entry :factory presence is enforced by the
    ;; berth's :manifest :schema rather than by read-manifest itself,
    ;; so the manifest reader no longer rejects this shape outright.

    (it "rejects v1 manifests that use :extends"
      (spit (.getPath @tmp-file)
            (pr-str {:id :foo :version "1.0" :extends {:comm {:pigeon {:factory 'foo/make}}}}))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects manifests with :requires (removed in v2)"
      (spit (.getPath @tmp-file) (pr-str (assoc pigeon-manifest :requires [])))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects manifests with :isaac/factory (v1 namespace)"
      (spit (.getPath @tmp-file)
            (pr-str {:id :foo :version "1.0"
                     :comm {:pigeon {:isaac/factory 'foo/make}}}))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects manifests with unknown top-level kind"
      (spit (.getPath @tmp-file)
            (pr-str {:id :foo :version "1.0"
                     :mystery {:echo {:factory 'foo/bar}}}))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects provider manifest entry missing :template"
      (spit (.getPath @tmp-file)
            (pr-str {:id :foo :version "1.0"
                     :provider {:my-prov {:api "chat-completions"}}}))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects comm manifest entry missing :factory"
      (spit (.getPath @tmp-file)
             (pr-str {:id :foo :version "1.0"
                      :comm {:my-comm {:schema {:token {:type :string}}}}}))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects tool manifest entry missing :factory"
      (spit (.getPath @tmp-file)
            (pr-str {:id :foo :version "1.0"
                     :tools {:doodad {:schema {:api-key {:type :string}}}}}))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects slash-command manifest entry missing :factory"
      (spit (.getPath @tmp-file)
            (pr-str {:id :foo :version "1.0"
                     :slash-commands {:echo {:schema {:command-name {:type :string}}}}}))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects tool manifest entry with :sort-index"
      (spit (.getPath @tmp-file)
            (pr-str {:id :foo :version "1.0"
                     :tools {:doodad {:factory 'isaac.tool.doodad/doodad-tool
                                      :sort-index 1}}}))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects slash-command manifest entry with :sort-index"
      (spit (.getPath @tmp-file)
            (pr-str {:id :foo :version "1.0"
                     :slash-commands {:echo {:factory 'isaac.slash.echo/echo-command
                                             :sort-index 1}}}))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects missing :id with a clear error"
      (spit (.getPath @tmp-file) (pr-str (dissoc pigeon-manifest :id)))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects missing :version with a clear error"
      (spit (.getPath @tmp-file) (pr-str (dissoc pigeon-manifest :version)))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects a top-level :factory that is not a symbol"
      (spit (.getPath @tmp-file)
            (pr-str {:id      :marigold.bridge
                     :version "1.0.0"
                     :factory "marigold.bridge/create-module"}))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    (it "rejects legacy :entry"
      (spit (.getPath @tmp-file) (pr-str (assoc pigeon-manifest :entry 'isaac.comm.pigeon)))
      (should-throw Exception (sut/read-manifest (.getPath @tmp-file) (fs/real-fs))))

    ;; Phase 5 of the berth epic: per-entry shape rejection for routes
    ;; (malformed [method path] keys, non-symbol handlers, etc.) is now
    ;; handled by the :isaac.server/route berth's :manifest :schema
    ;; rather than by read-manifest. The old "rejects malformed route
    ;; keys" / "rejects route handlers that are not symbols" tests
    ;; covered the deleted validate-routes! pass.

    (it "strips unknown scalar top-level keys and warns"
      (spit (.getPath @tmp-file) (pr-str (assoc pigeon-manifest :unknown-field "oops")))
      (let [result (log/capture-logs (sut/read-manifest (.getPath @tmp-file) (fs/real-fs)))]
        (should-not (contains? result :unknown-field))
        (should (some #(= :manifest/unknown-key (:event %)) @log/captured-logs)))))

  (describe "verify-schema-lexes on :comm :schema fragments"

    #_{:clj-kondo/ignore [:invalid-arity :unresolved-symbol]}
    (around [example]
      (binding [schema/*lexicon* schema/default-lexicon]
        (example)))

    (it ":validations [:present?] roundtrips"
      (let [frag {:loft {:type :string :validations [:present?]}}]
        (should= true (schema/verify-schema-lexes frag))))

    (it "factory ref [[:> 5]] roundtrips"
      (let [frag {:score {:type :int :validations [[:> 5]]}}]
        (should= true (schema/verify-schema-lexes frag))))

    (it "unregistered ref fails verify-schema-lexes"
      (let [frag {:foo {:type :string :validations [:does-not-exist?]}}]
        (should-throw Exception (schema/verify-schema-lexes frag))))))

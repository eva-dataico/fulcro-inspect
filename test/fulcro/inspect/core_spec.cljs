(ns fulcro.inspect.core-spec
  (:require [fulcro-spec.core :refer [specification behavior component assertions when-mocking]]
            [fulcro.inspect.core :as ic]
            [fulcro.client.primitives :as fp]))

(fp/defsc MyRoot [_ _] {})

(def my-root (fp/factory MyRoot))

(specification "app-id"
  (when-mocking
    (fp/reconciler? _) => true
    (assertions
      "Read from app db when information is there"
      (ic/app-id {:config {:state (atom {::ic/app-id "hello"})}})
      => "hello"

      "Use root class symbol from app when state doesn't have an id"
      (ic/app-id {:config {:state (atom {})}
                  :state  (atom {:root (my-root {})})})
      => `MyRoot)))

(specification "normalize-id"
  (assertions
    (ic/normalize-id 'sym) => 'sym
    (ic/normalize-id :key) => :key
    (ic/normalize-id "str") => "str"
    (ic/normalize-id 'sym-0) => 'sym
    (ic/normalize-id :key-10) => :key
    (ic/normalize-id "str-42") => "str"))

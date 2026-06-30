(ns syosetsuka.server-test
  (:require [clojure.test :refer [deftest is]]
            [jsonista.core :as j]
            [syosetsuka.server :as server]))

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (server/app
    (cond-> {:request-method method :uri uri :headers {"accept" "application/json"}}
      body (assoc :headers {"accept" "application/json" "content-type" "application/json"}
                  :body (java.io.ByteArrayInputStream. (.getBytes (j/write-value-as-string body))))))))

(defn- json-body [resp]
  (j/read-value (:body resp) j/keyword-keys-object-mapper))

(deftest health-and-ok
  (let [ok (json-body (req :get "/ok"))]
    (is (= 200 (:status (req :get "/ok"))))
    (is (= true (:ok ok)))
    (is (= 12 (count (:graphs ok)))))
  (is (= true (:ok (json-body (req :get "/health"))))))

(deftest runs-and-xrpc
  (let [run (req :post "/runs" {:assistant_id "generate_author" :input {:theme "冒険"}})
        xrpc (req :post "/xrpc/ai.gftd.apps.syosetsuka.generateAuthor" {:theme "冒険"})]
    (is (= 200 (:status run)))
    (is (= "created" (get-in (json-body run) [:result :status])))
    (is (= 200 (:status xrpc)))
    (is (= "created" (:status (json-body xrpc))))))

(deftest unknown-404
  (is (= 404 (:status (req :post "/runs" {:assistant_id "nope" :input {}}))))
  (is (= 404 (:status (req :post "/xrpc/ai.gftd.apps.syosetsuka.nope" {})))))

(ns app.core-test
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as http]
            [cheshire.core :as cheshire]
            [app.core :as app]
            [io.pedestal.test :as pedestal.test]
            [com.walmartlabs.lacinia.pedestal :as pedestal]
            [clojure.java.jdbc :as jdbc]))

(defn gen-app
  []
  (let [db {:dbtype "postgresql"
            :dbname (str `app#)
            :host   "localhost"
            :user   "postgres"}]
    (app/init-db! db)
    (-> (app/get-compiled-schema)
        (pedestal/service-map {:app-context {::jdbc/db db}
                               :env         :prod})
        (assoc ::jdbc/db db)
        (http/create-server))))

(defn gql!
  ([app query] (gql! app query {}))
  ([app query variables]
   (-> (pedestal.test/response-for (::http/service-fn app)
                                   :post "/graphql"
                                   :headers {"Content-Type" "application/json"}
                                   :body (cheshire/generate-string {:query     query
                                                                    :variables variables}))
       :body
       (as-> % (try
                 (cheshire/parse-string % true)
                 (catch Throwable _
                   %))))))

(defn destroy!
  [{::jdbc/keys [db]}]
  (jdbc/execute! (assoc db :dbname "")
                 (str "DROP DATABASE IF EXISTS " (:dbname db) ";")
                 {:transaction? false}))


(deftest integracao
  (let [app (gen-app)]
    (is (= (gql! app "mutation { meCria(nome: \"souenzzo\") { nome todos { id } } }")
           {:data {:meCria {:nome  "souenzzo"
                            :todos []}}}))
    (is (= (gql! app "{ eu(nome:\"souenzzo\") { nome todos { id } } }")
           {:data {:eu {:nome  "souenzzo"
                        :todos []}}}))
    (is (= (gql! app "mutation { novoTodo(descricao: \"graphql\", nome_usuario: \"souenzzo\") { nome todos { descricao } } }")
           {:data {:novoTodo {:nome  "souenzzo"
                              :todos [{:descricao "graphql"}]}}}))
    (let [{:keys [id]} (first (jdbc/query (::jdbc/db app) ["SELECT id FROM todo"]))]
      (is (= (gql! app
                   "mutation DeletaTodo ($id: ID!) { deletaTodo(id: $id)  { nome todos { id } } }"
                   {:id id})
             {:data {:deletaTodo {:nome  "souenzzo"
                                  :todos []}}})))
    (destroy! app)))
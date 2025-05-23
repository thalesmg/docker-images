(ns spark-query.core
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [cider.nrepl :refer [cider-nrepl-handler]]
   [nrepl.server :as nrepl-server]
   [org.httpkit.server :as server]
   [ring.middleware.reload :refer [wrap-reload]]
   [ring.util.request :as ring-req]
   [compojure.core :refer [defroutes GET POST]])
  (:import
   [java.util Properties]
   [org.apache.iceberg
    CatalogProperties]
   [org.apache.iceberg.catalog
    Catalog
    Namespace
    TableIdentifier]
   [org.apache.iceberg.data
    IcebergGenerics
    Record]
   [org.apache.iceberg.rest RESTCatalog]
   [org.apache.iceberg.aws.s3 S3FileIOProperties]
   [org.apache.hadoop.conf Configuration]
   [org.apache.spark.sql SparkSession])
  (:gen-class))

(def PORT 8090)
(def NREPL-PORT 7890)

(defn- record->vec
  [record]
  (let [size (.size record)
        columns (->> record
                     .struct
                     .fields
                     (mapv #(.name %)))
        values (mapv #(.get record %) (range size))]
    (zipmap columns values)))

(defn open-catalog
  []
  (let [catalog-props {CatalogProperties/CATALOG_IMPL "org.apache.iceberg.rest.RESTCatalog"
                       CatalogProperties/URI "http://iceberg-rest:8181"
                       CatalogProperties/WAREHOUSE_LOCATION, "s3a://warehouse/wh"
                       CatalogProperties/FILE_IO_IMPL "org.apache.iceberg.aws.s3.S3FileIO"
                       S3FileIOProperties/ENDPOINT "http://minio.net:9000"}
        catalog (RESTCatalog.)
        catalog-config (Configuration.)
        _ (doto catalog
            (.setConf catalog-config)
            (.initialize "demo" catalog-props))]
    catalog))

(defn spark-session
  []
  (-> (SparkSession/builder)
      (.config "spark.sql.defaultCatalog" "demo")
      (.config "spark.sql.catalog.demo" "org.apache.iceberg.spark.SparkCatalog")
      (.config "spark.sql.catalog.demo.type" "rest")
      (.config "spark.sql.catalog.demo.uri" "http://iceberg-rest:8181")
      (.config "spark.sql.catalog.demo.io-impl" "org.apache.iceberg.aws.s3.S3FileIO")
      (.config "spark.sql.catalog.demo.warehouse" "s3://warehouse/wh/")
      (.config "spark.sql.catalog.demo.s3.endpoint" "http://minio.net:9000")
      (.master "local")
      .getOrCreate))

(def CATALOG (atom nil))

(defn get-catalog
  []
  (if @CATALOG
    @CATALOG
    (let [catalog (open-catalog)]
      (reset! CATALOG catalog)
      catalog)))

(defn load-table
  [catalog ns table]
  (let [ns-id (Namespace/of (into-array String [ns]))
        table-id (TableIdentifier/of ns-id table)
        table (.loadTable catalog table-id)]
    table))

(defn scan-table
  [table]
  (-> table
      IcebergGenerics/read
      .build
      .iterator
      iterator-seq
      (into [])))

(defn partition-data->vec
  [pdata]
  (let [size (.size pdata)
        fields (->> pdata
                    .getPartitionType
                    .fields
                    (mapv #(.name %)))
        values (mapv #(.get pdata %) (range size))]
    (zipmap fields values)))

(defn table-partitions-from-meta
  [table]
  (letfn [(content-data-file->vec [content-data-file]
            (-> content-data-file
                .partition
                partition-data->vec))]
    (-> table
        .currentSnapshot
        (.addedDataFiles (.io table))
        (->> (map content-data-file->vec)))))

(defn table-partitions-from-data
  [table]
  (-> table
      .newScan
      .planTasks
      .iterator
      iterator-seq
      (->> (mapcat #(.files %))
           (map #(-> %
                     .partition
                     partition-data->vec)))))

(defn handle-scan-table
  [ns-in table-in]
  (let [table (load-table (get-catalog) ns-in table-in)
        rows (scan-table table)
        response-body (->> rows
                           (mapv record->vec)
                           json/write-str)]
    {:body response-body}))

(defn handle-table-partitions
  [ns-in table-in]
  (let [table (load-table (get-catalog) ns-in table-in)
        partitions-from-meta (table-partitions-from-meta table)
        partitions-from-data (table-partitions-from-data table)
        response {:from-data partitions-from-data
                  :from-meta partitions-from-meta}
        response-body (json/write-str response)]
    {:body response-body}))

(defn handle-spark-sql
  [request]
  (let [sql (ring-req/body-string request)
        session (spark-session)
        dataset (.sql session sql)
        _ (.show dataset)
        response-body (-> dataset
                          .toJSON
                          .toLocalIterator
                          iterator-seq
                          (->> (map json/read-str))
                          json/write-str)]
    {:body response-body}))

(defroutes app-routes
  (GET "/scan/:ns/:table" [ns table] (handle-scan-table ns table))
  (GET "/partitions/:ns/:table" [ns table] (handle-table-partitions ns table))
  (POST "/sql" request (handle-spark-sql request)))

(defn- block-forever
  []
  (while true
    (Thread/sleep 60000)))

(defn -main
  [& _args]
  (try
    (println "starting nREPL server on port" NREPL-PORT)
    (nrepl-server/start-server :port NREPL-PORT :bind "0.0.0.0" :handler cider-nrepl-handler)
    (println "started nREPL server on port" NREPL-PORT)
    (println "starting server on port" PORT)
    (server/run-server (wrap-reload #'app-routes) {:port PORT})
    (println "started server on port" PORT)
    (block-forever)
    (catch Exception e
      (println (.getMessage e))
      (.printStackTrace e)
      (System/exit 1))))

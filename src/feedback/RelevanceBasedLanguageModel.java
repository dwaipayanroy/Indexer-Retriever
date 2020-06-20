/**
 * RM3: Complete;
 * Relevance Based Language Model with query mix.
 * References:
 *      1. Relevance Based Language Model - Victor Lavrenko - SIGIR-2001
 *      2. UMass at TREC 2004: Novelty and HARD - Nasreen Abdul-Jaleel et al.- TREC-2004
 *      3. Condensed List Relevance Model - Fernando Diaz - ICTIR 2016
 */
package feedback;

import common.CollectionStatistics;
import common.Luc4TRECQuery;
import static common.trec.DocField.FIELD_BOW;
import static common.trec.DocField.FIELD_ID;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.Similarity;
import common.trec.TRECQuery;
import common.trec.TRECQueryParser;
import java.util.ArrayList;
import org.apache.lucene.search.Query;
import searcher.Searcher;

/**
 *
 * @author dwaipayan
 */

public class RelevanceBasedLanguageModel extends Searcher {

    String          fieldForFeedback;   // field, to be used for feedback
    long            vocSize;            // vocabulary size
    RLM             rlm;
    Boolean         feedbackFromFile;

    List<TRECQuery> queries;
    TRECQueryParser trecQueryparser;

    CollectionStatistics    collStat;

    HashMap<String, TopDocs> allTopDocsFromFileHashMap;     // For feedback from file, to contain all topdocs from file

    // +++ TRF
    String qrelPath;
    boolean trf;    // true or false depending on whether True Relevance Feedback is choosen
    HashMap<String, TopDocs> allRelDocsFromQrelHashMap;     // For TRF, to contain all true rel. docs.
    // --- TRF

    float           mixingLambda;    // mixing weight, used for doc-col weight distribution
    int             numFeedbackTerms;// number of feedback terms
    int             numFeedbackDocs; // number of feedback documents
    float           QMIX;

    /**
     *
     * @param propPath
     * @throws IOException
     * @throws Exception
     */
    public RelevanceBasedLanguageModel(String propPath) throws IOException, Exception {

        super(propPath);

        /* constructing the query */
        fieldToSearch = prop.getProperty("fieldToSearch", FIELD_BOW);
        System.out.println("Searching field for retrieval: " + fieldToSearch);

        trecQueryparser = new TRECQueryParser(queryPath, analyzer);
        trecQueryparser.queryFileParse();
        queries = trecQueryparser.queries;
        /* constructed the query */

        fieldForFeedback = prop.getProperty("fieldForFeedback", FIELD_BOW);
        System.out.println("Field for Feedback: " + fieldForFeedback);

        if(Boolean.parseBoolean(prop.getProperty("rm3.rerank", "false"))) {
            collStat = new CollectionStatistics(indexPath, fieldForFeedback);
            collStat.buildCollectionStat();
            System.out.println("Collection Statistics building completed");
        }

        // +++ TRF
        trf = Boolean.parseBoolean(prop.getProperty("toTRF","false"));
        if(trf) {
            qrelPath = prop.getProperty("qrelPath");
            ArrayList<String> queryIds = new ArrayList<>();
            for (TRECQuery query : queries)
                queryIds.add(query.qid);

            allRelDocsFromQrelHashMap = common.CommonMethods.readJudgedDocsFromQrel(qrelPath, queryIds, indexReader, FIELD_ID, 1);
        }
        // --- TRF

        // numFeedbackTerms = number of top terms to select for query expansion
        numFeedbackTerms = Integer.parseInt(prop.getProperty("numFeedbackTerms"));
        // numFeedbackDocs = number of top documents to select for feedback
        numFeedbackDocs = Integer.parseInt(prop.getProperty("numFeedbackDocs"));

        if(param1>0.99)
            mixingLambda = 0.8f;
        else
            mixingLambda = param1;

        QMIX = Float.parseFloat(prop.getProperty("rm3.queryMix"));

        rlm = new RLM(indexReader, indexSearcher, analyzer, fieldToSearch, fieldForFeedback, numFeedbackDocs, numFeedbackTerms, mixingLambda, QMIX);

        setRunName();
    }

    /**
     * Sets runName and resPath variables depending on similarity functions.
     */
    private void setRunName() throws IOException {

        Similarity s = indexSearcher.getSimilarity(true);
        runName = queryFile.getName()+"-"+s.toString()+"-D"+numFeedbackDocs+"-T"+numFeedbackTerms;
        runName += "-rm3-"+Float.parseFloat(prop.getProperty("rm3.queryMix", "0.98"));
        runName += "-" + fieldToSearch + "-" + fieldForFeedback;
        runName = runName.replace(" ", "").replace("(", "").replace(")", "").replace("00000", "");

        if(Boolean.parseBoolean(prop.getProperty("rm3.rerank")) == true)
            runName += "-rerank";
        setResFileName(runName);

    } // ends setResFileName()

    public static TopDocs search(IndexSearcher searcher, Query query, int numHits) throws IOException {

        return searcher.search(query, numHits);
    }

    public TopDocs retrieve(TRECQuery query, int numFeedbackDocs) throws Exception {

        query.fieldToSearch = fieldToSearch;
        query.luceneQuery = query.makeBooleanQuery(query.qtitle, fieldToSearch, analyzer);
        query.q_str = query.luceneQuery.toString(fieldToSearch);

        System.out.println(query.qid+": \t" + query.luceneQuery.toString(fieldToSearch));

        return retrieve(query.luceneQuery, numFeedbackDocs);
    }
    
    public TopDocs retrieve(Query luceneQuery, int numHits) throws Exception {
        
        TopDocs topDocs;

        topDocs = search(luceneQuery, numHits);

        return topDocs;
    }

    public void retrieveAll() throws Exception {

        TopDocs topDocs;
        ScoreDoc[] hits;

        for (TRECQuery query : queries) {

            switch("feedbackChoice") {
                case "trf":
                    // +++ TRF
                    System.out.println("TRF from qrel");
                    if(null == (topDocs = allRelDocsFromQrelHashMap.get(query.qid))) {
                        System.err.println("Error: Query id: "+query.qid+
                            " not present in qrel file");
                        continue;
                    }
                    numFeedbackDocs = topDocs.scoreDocs.length;
                    // --- TRF
                    break;
                case "prf":
                default:
                    // +++ PRF
                    // + Initial retrieval
                    topDocs = retrieve(query, numFeedbackDocs);
                    // - Initial retrieval
                    // --- PRF
                    break;
            }

            if(topDocs.totalHits == 0)
                System.out.println(query.qid + ": documents retrieve: " + 0);

            else {
                // + expanded retrieval
                Query rm1_query = rlm.expandQuery(topDocs, query);
                topDocs = retrieve(rm1_query, numHits);
                // - expanded retrieval

                hits = topDocs.scoreDocs;
                if(hits == null)
                    System.out.println("expanded retrieval: documents retrieve: " + 0);

                else {
                    System.out.println(query.qid + ": expanded retrieval: documents retrieve: " +hits.length);
                    StringBuffer resBuffer = makeTRECResFile(query.qid, hits, indexSearcher, runName, FIELD_ID);
                    resFileWriter.write(resBuffer.toString());
                }
            }
        } // ends for each query
        resFileWriter.close();
    } // ends retrieveAll

    public static void main(String[] args) throws IOException, Exception {

        String usage = "java RelevanceBasedLanguageModel <properties-file>\n"
            + "Properties file must contain the following fields:\n"
            + "1. stopFilePath: path of the stopword file\n"
            + "2. fieldToSearch: field of the index to be searched\n"
            + "3. indexPath: Path of the index\n"
            + "4. queryPath: path of the query file (in proper xml format)\n"
            + "5. numFeedbackTerms: number of feedback terms to use\n"
            + "6. numFeedbackDocs: number of feedback documents to use\n"
            + "7. [numHits]: default-1000 - number of documents to retrieve\n"
            + "8. rm3.queryMix (0.0-1.0): query mix to weight between P(w|R) and P(w|Q)\n"
            + "9. [rm3.rerank]: default-0 - 1-Yes, 0-No\n"
            + "10. resPath: path of the folder in which the res file will be created\n"
            + "11. similarityFunction: 0.DefaultSimilarity, 1.BM25Similarity, 2.LMJelinekMercerSimilarity, 3.LMDirichletSimilarity\n"
            + "12. param1: \n"
            + "13. [param2]: optional if using BM25\n";

        if(1 != args.length) {
            System.out.println("Usage: " + usage);
            args = new String[1];
            args[0] = "trec123.xmlrm3.D-10.T-40.S-content.F-content.properties";
//            args[0] = "trblm-T-100.properties";
//            System.exit(1);
        }
        RelevanceBasedLanguageModel rblm = new RelevanceBasedLanguageModel(args[0]);

        rblm.retrieveAll();
    } // ends main()

}

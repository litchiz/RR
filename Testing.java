package sourcedg;

import java.io.FileInputStream;
import java.sql.Array;
import java.sql.SQLData;
import java.util.*;
import java.util.stream.Collectors;

import sourcedg.analysis.PDGSlicer;
import sourcedg.builder.PDGBuilder;
import sourcedg.builder.PDGBuilderConfig;
import sourcedg.graph.*;
import sourcedg.util.GraphExporter;


public class Testing {

    final ArrayList<Set<Vertex>> forwards = new ArrayList<>();//对这些sink进行前向边的追溯-其中每一项为Ss
    final ArrayList<Vertex> actualin = new ArrayList<>();//[24-Actual-in-sql_delete, 20-Actual-in-sql_move]等actual in节点
    final ArrayList<Vertex> sqls = new ArrayList<>();//actual in的sql语句参数sql_delete = "DELETE FROM " + table + " WHERE newsId='" + newsid + "'"
    final ArrayList<String> sqlstrings = new ArrayList<>();
    final ArrayList<Set<Vertex>> slices = new ArrayList<>();//对每路径进行切片(query path相关)
    final ArrayList<Set<Vertex>> ctrlSlice = new ArrayList<>();
    final List<Set<String>> sum_contents = new ArrayList<>();//限制条件
    final Set<Vertex> vs = new HashSet<>();
    final ArrayList<ArrayList<String>> action_tables = new ArrayList<>();
    final ArrayList<ArrayList<String>> content_sets = new ArrayList<>();
    final ArrayList<ArrayList<ArrayList<String>>> items = new ArrayList<>();
    final ArrayList<Integer> mistake = new ArrayList<>();


    public void slicing(PDG pdg) {
        vs.addAll(pdg.vertexSet().stream().filter(u -> u.getLabel().contains("execute")).collect(Collectors.toSet()));
        final Set<Vertex> vs2 = pdg.vertexSet().stream().filter(u -> u.getLabel().contains("prepareStatement")).collect(Collectors.toSet());
        final Set<Vertex> vs3 = pdg.vertexSet().stream().filter(u -> u.getLabel().contains("createQuery")).collect(Collectors.toSet());
        vs.addAll(vs2);
        vs.addAll(vs3);
        for (final Vertex v : vs) {
            //包括sql查询自己的节点的前向追溯
            final Set<Vertex> Ss =
                    pdg.incomingEdgesOf(v).stream().map(e -> e.getSource()).collect(Collectors.toSet());
            Ss.add(v);
            forwards.add(Ss);
            Set<Edge> edges = pdg.outgoingEdgesOf(v);
            boolean hasactualin = false;
            //actualin表示对应sql语句（string），可往上追溯到含有（INSERT/DELETE/SELECT/UPDATE等表述）
            for (Edge e : edges) {

                if (e.getTarget().getType() == VertexType.ACTUAL_IN) {
                    actualin.add(e.getTarget());
                    hasactualin = true;
                }
            }
            if (!hasactualin) {
                actualin.add(pdg.getEntry());
            }
            final Set<Vertex> slice = PDGSlicer.backward(pdg, Ss);
            //System.out.println("slice "+slice);
            slices.add(slice);
        }

    }


    public void getSQLStrings(PDG pdg) {
        for (int i = 0; i < actualin.size(); i++) {
            Vertex vi = actualin.get(i);
            final Set<Vertex> actualins = pdg.incomingEdgesOf(vi).stream().map(e -> e.getSource()).collect(Collectors.toSet());
            //System.out.println(actualins);
            final List<Vertex> tmp = actualins.stream().filter(u -> u.getLabel().contains("delete")||(u.getLabel().contains("DELETE") || (u.getLabel().contains("select")) ||(u.getLabel().contains("insert")) || (u.getLabel().contains("INSERT")) || (u.getLabel().contains("SELECT")) || (u.getLabel().contains("UPDATE"))||(u.getLabel().contains("update")))).collect(Collectors.toList());

            if (!tmp.isEmpty()) {
                final Vertex sql = tmp.get(0);

                sqls.add(sql);
                sqlstrings.add(sql.getLabel());
            } else
            {
                sqlstrings.add("null");
                sqls.add(pdg.getEntry());
            }
        }
    }


    public void getCtrl(PDG pdg, ArrayList<String> hints) {


        for (final Set<Vertex> as : slices) {
            final Set<Vertex> ctrlEdge = new HashSet<>();
            for (final Vertex v : as)  //all the ctrl expression
            {
                if (v.getType() == VertexType.CTRL) {
                    ctrlEdge.add(v);
                }
            }
            ctrlSlice.add(PDGSlicer.backward(pdg, ctrlEdge));
        }


        for (int j = 0; j < ctrlSlice.size(); j++) {
            Set<Vertex> as = ctrlSlice.get(j);
            Set<String> contents = new HashSet<>();
            {
                for (final Vertex v : as) {
                    for (String hint : hints) {
                        if (v.getLabel().contains(hint) && ((v.getLabel().contains("equals"))||(v.getLabel().contains("==")))) {
                            String content = v.getLabel();
                            if (!contents.contains(content)) {

                                contents.add(content);
                            }
                        }
                    }
                }
            }
            sum_contents.add(contents);
        }
    }

    public void getActionTable() {
        for (int i = 0; i < sqlstrings.size(); i++) {
             String sqlstring = sqlstrings.get(i);//每一个sql语句
            ArrayList<String> action_table = new ArrayList<>();
            if(sqlstring.contains("prepareStatement"))
            {
                String actualinstring = actualin.get(i).getLabel();//每一个sql语句
                //System.out.println("sqlstring+ "+sqlstring);
                //System.out.println("i "+i);

                if (actualinstring != "null") {
                    String[] sqlstringarray = actualinstring.split("\\s+");
                    //System.out.println("sqlstringarray[2]+ "+sqlstringarray[2]);
                    if (sqlstringarray[0].equals("\"insert")) {
                        String tablename = sqlstringarray[2];
                        action_table.add("INSERT");
                        action_table.add(tablename);
                    /*if (sqlstringarray[5].equals("delete") || sqlstringarray[5].equals("update") || sqlstringarray[5].equals("select")) {
                        action_table.add(sqlstringarray[5]);
                        action_table.add(sqlstringarray[8]);
                    }*/
                        //System.out.println("action_table+ "+action_table);
                    } else if (sqlstringarray[0].equals("\"delete")) {
                        action_table.add("DELETE");
                        action_table.add(sqlstringarray[2]);
                    } else if (sqlstringarray[0].equals("\"update")) {
                        action_table.add("UPDATE");
                        action_table.add(sqlstringarray[1]);
                    } else if (sqlstringarray[0].equals("\"select")) {
                        action_table.add("SELECT");
                        action_table.add(sqlstringarray[3]);
                    }
                    action_tables.add(action_table);
                    //System.out.println("action_table+ "+action_table);
                } else {
                    action_tables.add(action_table);
                    //System.out.println("action_table+ "+action_table);
                }
            }else {
                //System.out.println("sqlstring+ "+sqlstring);
                //System.out.println("i "+i);
                //ArrayList<String> action_table = new ArrayList<>();
                if (sqlstring != "null") {
                    String[] sqlstringarray = sqlstring.split("\\s+");
                    //System.out.println("sqlstringarray[2]+ "+sqlstringarray[2]);
                    if (sqlstringarray[2].equals("\"INSERT")) {
                        String tablename = sqlstringarray[4];
                        action_table.add("INSERT");
                        action_table.add(tablename);
                        if (sqlstringarray[5].equals("DELETE") || sqlstringarray[5].equals("UPDATE") || sqlstringarray[5].equals("select") || sqlstringarray[5].equals("SELECT")) {
                            action_table.add(sqlstringarray[5]);
                            action_table.add(sqlstringarray[8]);
                        }
                        //System.out.println("action_table+ "+action_table);
                    } else if (sqlstringarray[2].equals("\"DELETE")) {
                        action_table.add("DELETE");
                        action_table.add(sqlstringarray[4]);
                    } else if (sqlstringarray[2].equals("\"UPDATE")) {
                        action_table.add("UPDATE");
                        action_table.add(sqlstringarray[3]);
                    } else if (sqlstringarray[2].equals("\"select") || sqlstringarray[2].equals("\"SELECT")) {
                        action_table.add("SELECT");
                        action_table.add(sqlstringarray[5]);
                    }
                    action_tables.add(action_table);
                    //System.out.println("action_table+ "+action_table);
                }
            else {
                action_tables.add(action_table);
                //System.out.println("action_table+ "+action_table);
            }
            }

        }
    }

    public void getCtrlStrings(ArrayList<String> hints) {
        for (int i = 0; i < sum_contents.size(); i++) {
            Set<String> contents = sum_contents.get(i);
            ArrayList<String> quad = new ArrayList<>();
            if (!contents.isEmpty()) {
                for (String condition : contents) {
                    //System.out.println(condition);
                    if (!condition.contains("||")) {
                        for (int n = 0; n < hints.size(); n++) {
                            if (condition.contains(hints.get(n))) {
                                int index_role = condition.indexOf(hints.get(n));
                                if(condition.contains("equals"))
                                {
                                    int index_left = condition.indexOf("(", index_role);
                                    int index_right = condition.indexOf(")", index_left + 1);
                                    quad.add(condition.substring(index_left + 1, index_right));
                                }
                                else if(condition.contains("=="))
                                {
                                    int index_left = condition.indexOf("==", index_role);
                                    //int index_right = condition.indexOf(")", index_left + 1);
                                    quad.add(condition.substring(index_left + 2,condition.length()));
                                }



                            } else {
                                quad.add("-");
                            }
                        }
                    } else if (!condition.equals("")) {
                        String[] condition_sub = condition.split("\\|\\|");
                        for (int j = 0; j < condition_sub.length; j++) {
                            String tmp = condition_sub[j];
                            for (int n = 0; n < hints.size(); n++) {
                                if (tmp.contains(hints.get(n))) {
                                    int index_role = condition.indexOf(hints.get(n));
                                    if(condition.contains("equals"))
                                    {
                                        int index_left = condition.indexOf("(", index_role);
                                        int index_right = condition.indexOf(")", index_left + 1);
                                        quad.add(condition.substring(index_left + 1, index_right));
                                    }
                                    else if(condition.contains("=="))
                                    {
                                        int index_left = condition.indexOf("==", index_role);
                                        //int index_right = condition.indexOf(")", index_left + 1);
                                        quad.add(condition.substring(index_left + 2,condition.length()));
                                    }
                                } else {
                                    quad.add("-");
                                }
                            }
                            // System.out.println(tmp);
                        }
                    }
                }
            }else {
                        for (int n = 0; n < hints.size(); n++) {
                            quad.add("-");
                        }

                    }

                    content_sets.add(quad);


        }
    }

    public void resourceAccessAnalyse() {
        for (int i = 0; i < vs.size(); i++) {
            //ArrayList<ArrayList<String>> result = new ArrayList<>();
            for (int j = 1; j < action_tables.get(i).size(); j = j + 2) {
                ArrayList<ArrayList<String>> item = new ArrayList<>();
                //Object[] item=new Object[4];
                String table = action_tables.get(i).get(j);
                String action = action_tables.get(i).get(j - 1);
                ArrayList<String> condition = content_sets.get(i);
                ArrayList<String> meta = new ArrayList<>();
                if (condition.size() > 3) {

                    for (int k = 2; k < condition.size(); k = k + 3) {
                        item.clear();
                        meta.clear();
                        ArrayList<String> condition_sub = new ArrayList<>();
                        condition_sub.add(condition.get((k - 2)));
                        condition_sub.add(condition.get((k - 1)));
                        condition_sub.add(condition.get((k)));
                        meta.add(table);
                        meta.add(action);
                        meta.add(String.valueOf(i));

                        item.add(meta);
                        item.add(condition_sub);
                        items.add(item);
                        // System.out.println(item);
                    }
                } else {
                    item.clear();
                    meta.clear();
                    meta.add(table);
                    meta.add(action);
                    meta.add(String.valueOf(i));

                    item.add(meta);
                    item.add(condition);
                    items.add(item);

                }
                //System.out.println(item);
            }


        }

    }

    public void contextConflict(ArrayList<String> hints) {
        for (int i = 0; i < items.size(); i++) {
            ArrayList<ArrayList<String>> itemi = items.get(i);
            //System.out.println(itemi.get(0).get(1));
            for (int j = 0; j < items.size(); j++) {
                ArrayList<ArrayList<String>> itemj = items.get(j);

                if (itemi.get(0).get(0).equals(itemj.get(0).get(0)))//同一张表
                {

                    if (itemi.get(0).get(1).equals("INSERT") && (itemj.get(0).get(1).equals("UPDATE") || itemj.get(0).get(1).equals("DELETE"))) {
                        for (int k = 0; k < hints.size(); k++) {
                            if (!(Objects.equals(itemi.get(1).get(k), "-") || Objects.equals(itemi.get(1).get(k), itemj.get(1).get(k)))) {
                                int id = Integer.parseInt(itemj.get(0).get(2));
                                if (!mistake.contains(id))
                                    mistake.add(id);
                            }
                        }

                    }
                }
            }


        }
    }

    public static void main(final String[] args) throws Exception {
        final FileInputStream in = new FileInputStream("D:\\testing_javaweb\\testcode.java");

        ArrayList<String> hints = new ArrayList<>();
        hints.add("role");
        hints.add("user");
        hints.add("permission");
        Testing t=new Testing();
        PDGBuilderConfig config = PDGBuilderConfig.create().interproceduralCalls();
        //PDGBuilderConfig config2 = config.normalize();
        final PDGBuilder builder = new PDGBuilder(config);
        builder.build(in);
        final PDG pdg = builder.getPDG();
        t.slicing(pdg);
        t.getSQLStrings(pdg);
        t.getActionTable();
        t.getCtrl(pdg,hints);
        t.getCtrlStrings(hints);
        System.out.println(t.sum_contents);
        System.out.println(t.content_sets);
        System.out.println(t.sqlstrings.size());
        System.out.println(t.actualin.size());

        System.out.println(t.action_tables);
        t.resourceAccessAnalyse();
        t.contextConflict(hints);

        System.out.println(t.mistake);
        System.out.println(t.mistake.size());

        for(int i=0;i<t.mistake.size();i++)
        {
           int index=t.mistake.get(i);
            System.out.println(t.sqlstrings.get(index));
        }

        final FileInputStream in2 = new FileInputStream("D:\\testing_javaweb\\easyjspforum\\test-js2java\\test-java-simple\\test.java");

        ArrayList<String> hints2 = new ArrayList<>();
        hints2.add("username");
        hints2.add("type");
        hints2.add("curmemid");
        Testing t2=new Testing();
        PDGBuilderConfig config2 = PDGBuilderConfig.create().interproceduralCalls();
        //PDGBuilderConfig config2 = config.normalize();
        final PDGBuilder builder2 = new PDGBuilder(config2);
        builder2.build(in2);
        final PDG pdg2 = builder2.getPDG();
        t2.slicing(pdg2);
        t2.getSQLStrings(pdg2);
        t2.getActionTable();
        t2.getCtrl(pdg2,hints2);
        System.out.println(t2.sum_contents);
        t2.getCtrlStrings(hints2);

        System.out.println(t2.content_sets);
        System.out.println(t2.sqlstrings.size());
        System.out.println(t2.actualin.size());

        System.out.println(t2.action_tables);
        t2.resourceAccessAnalyse();
         t2.contextConflict(hints);

        System.out.println(t2.mistake);
        System.out.println(t2.mistake.size());

        for(int i=0;i<t2.mistake.size();i++)
        {
            int index=t2.mistake.get(i);
            System.out.println(t2.sqlstrings.get(index));
        }

        final FileInputStream in3 = new FileInputStream("D:\\testing_javaweb\\MySqlStudents-master\\MySqlStudents-master\\src\\com\\github\\jcpp\\mysql\\db\\dao\\StudentDAO.java");

        ArrayList<String> hints3 = new ArrayList<>();
        hints3.add("role");

        Testing t3=new Testing();
        PDGBuilderConfig config3 = PDGBuilderConfig.create().interproceduralCalls();
        //PDGBuilderConfig config2 = config.normalize();
        final PDGBuilder builder3 = new PDGBuilder(config3);
        builder3.build(in3);
        final PDG pdg3 = builder3.getPDG();
        t3.slicing(pdg3);
        t3.getSQLStrings(pdg3);
        t3.getActionTable();
        t3.getCtrl(pdg3,hints3);
        System.out.println(t3.sum_contents);
        t3.getCtrlStrings(hints3);

        System.out.println(t3.content_sets);
        System.out.println(t3.sqlstrings.size());
        System.out.println(t3.actualin.size());
        System.out.println(t3.sqlstrings);

        System.out.println(t3.action_tables);
        t3.resourceAccessAnalyse();
        System.out.println(t3.items);
        t3.contextConflict(hints3);

        System.out.println(t3.mistake);
        System.out.println(t3.mistake.size());
        System.out.println(t3.sqls.size());
        System.out.println(t3.sqlstrings.size());
        for(int i=0;i<t3.mistake.size();i++)
        {
            int index=t3.mistake.get(i);
            System.out.println(t3.sqlstrings.get(index));
        }

        final FileInputStream in4 = new FileInputStream("D:\\testing_javaweb\\GYM-MANAGEMENT-master\\GYM-MANAGEMENT-master\\gym management\\src\\java\\testcode.java");

        ArrayList<String> hints4 = new ArrayList<>();
        hints4.add("pswd");

        Testing t4=new Testing();
        PDGBuilderConfig config4 = PDGBuilderConfig.create().interproceduralCalls();
        //PDGBuilderConfig config2 = config.normalize();
        final PDGBuilder builder4 = new PDGBuilder(config4);
        builder4.build(in4);
        final PDG pdg4 = builder4.getPDG();
        t4.slicing(pdg4);
        t4.getSQLStrings(pdg4);
        t4.getActionTable();
        t4.getCtrl(pdg4,hints4);
        System.out.println(t4.sum_contents);
        t4.getCtrlStrings(hints4);

        System.out.println(t4.content_sets);
        System.out.println(t4.content_sets.size());
        System.out.println(t4.action_tables.size());
        System.out.println(t4.sqlstrings);

        System.out.println(t4.action_tables);
        t4.resourceAccessAnalyse();
        System.out.println(t4.items);
        t4.contextConflict(hints4);

        System.out.println(t4.mistake);
        System.out.println(t4.mistake.size());
        System.out.println(t4.sqls.size());
        System.out.println(t4.sqlstrings.size());
        for(int i=0;i<t4.mistake.size();i++)
        {
            int index=t4.mistake.get(i);
            System.out.println(t4.sqlstrings.get(index));
        }

        /*final FileInputStream in5 = new FileInputStream("D:\\testing_javaweb\\pokedex-master\\pokedex-master\\src\\main\\java\\test.java");
        ArrayList<String> hints5 = new ArrayList<>();
        hints5.add("role");
        Testing t5=new Testing();
        PDGBuilderConfig config5 = PDGBuilderConfig.create().interproceduralCalls();

        final PDGBuilder builder5 = new PDGBuilder(config5);
        builder5.build(in5);
        final PDG pdg5 = builder5.getPDG();
        t5.slicing(pdg5);
        t5.getSQLStrings(pdg5);
        t5.getActionTable();
        t5.getCtrl(pdg5,hints5);*/


        /**/



        /**/
    }

}

package com.vncode.app.features.tnved;

import com.google.gson.JsonParser;
import com.vncode.app.features.tnved.TnvedModels.Node;
import com.vncode.app.features.tnved.TnvedModels.Version;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;

/** Own catalog storage. Never opens or changes the marketplace database. */
public final class TnvedCatalog {
    private final Path database;
    private static final List<String> SECTIONS = List.of("I","II","III","IV","V","VI","VII","VIII","IX","X","XI","XII","XIII","XIV","XV","XVI","XVII","XVIII","XIX","XX","XXI");
    private static final int[][] RANGES = {{1,5},{6,14},{15,15},{16,24},{25,27},{28,38},{39,40},{41,43},{44,46},{47,49},{50,63},{64,67},{68,70},{71,71},{72,83},{84,85},{86,89},{90,92},{93,93},{94,96},{97,97}};
    public TnvedCatalog(Path database) { this.database=database.toAbsolutePath().normalize(); }
    private Connection connect() throws SQLException {
        Connection c=DriverManager.getConnection("jdbc:sqlite:"+database);
        try(var s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}
        return c;
    }
    public synchronized void initialize() throws Exception {
        Files.createDirectories(database.getParent());
        try(var c=connect();var s=c.createStatement()) {
            try(var r=s.executeQuery("PRAGMA user_version")){if(r.next()&&r.getInt(1)>1)throw new SQLException("Newer TN VED schema is unsupported.");}
            s.execute("CREATE TABLE IF NOT EXISTS tnved_versions(id INTEGER PRIMARY KEY,label TEXT NOT NULL UNIQUE,source_url TEXT NOT NULL,valid_from TEXT,active INTEGER NOT NULL DEFAULT 0,verified INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS tnved_one_active ON tnved_versions(active) WHERE active=1");
            s.execute("""
                CREATE TABLE IF NOT EXISTS tnved_nodes(
                  id INTEGER PRIMARY KEY,version_id INTEGER NOT NULL REFERENCES tnved_versions(id),
                  parent_id INTEGER REFERENCES tnved_nodes(id),node_type TEXT NOT NULL,code TEXT NOT NULL,
                  section_code TEXT NOT NULL,name_ru TEXT NOT NULL,name_vi TEXT NOT NULL,
                  description_ru TEXT,notes TEXT,is_leaf INTEGER NOT NULL,is_active INTEGER NOT NULL,
                  valid_from TEXT,valid_to TEXT,source_url TEXT NOT NULL,source_version TEXT NOT NULL,
                  search_text TEXT NOT NULL,UNIQUE(version_id,code))
                """);
            s.execute("CREATE INDEX IF NOT EXISTS tnved_parent ON tnved_nodes(version_id,parent_id)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS tnved_product_mappings(
                  id INTEGER PRIMARY KEY,marketplace TEXT NOT NULL,shop_id INTEGER NOT NULL,product_id TEXT NOT NULL,
                  node_id INTEGER NOT NULL REFERENCES tnved_nodes(id),status TEXT NOT NULL,confirmed_at TEXT,
                  replaced_at TEXT)
                """);
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS tnved_one_primary ON tnved_product_mappings(marketplace,shop_id,product_id) WHERE status='CONFIRMED'");
            s.execute("CREATE TABLE IF NOT EXISTS tnved_classification_reviews(id INTEGER PRIMARY KEY,marketplace TEXT NOT NULL,shop_id INTEGER NOT NULL,product_id TEXT NOT NULL,version_id INTEGER REFERENCES tnved_versions(id),facts_json TEXT NOT NULL,result_json TEXT NOT NULL,provider TEXT,decision TEXT,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS marking_rule_versions(id INTEGER PRIMARY KEY,label TEXT NOT NULL UNIQUE,source_url TEXT NOT NULL,valid_from TEXT NOT NULL,valid_to TEXT,checksum TEXT NOT NULL,verified INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS marking_rules(id INTEGER PRIMARY KEY,version_id INTEGER NOT NULL REFERENCES marking_rule_versions(id),conditions_json TEXT NOT NULL,exceptions_json TEXT NOT NULL,outcome TEXT NOT NULL,citation TEXT NOT NULL)");
            s.execute("PRAGMA user_version=1");
            try(var r=s.executeQuery("SELECT count(*) FROM tnved_versions")) {
                if(r.next()&&r.getInt(1)>0)return;
            }
        }
        store(new Version("structure-observed-2026-10-09","https://www.consultant.ru/document/cons_doc_LAW_397176/",null), roots(),false);
    }
    private static List<Node> roots() throws Exception {
        var stream=TnvedCatalog.class.getResourceAsStream("/com/vncode/app/tnved/sections.json");
        if(stream==null)throw new IllegalStateException("Missing verified section resource.");
        try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
            List<Node> nodes=new ArrayList<>();
            for(var e:JsonParser.parseReader(reader).getAsJsonArray()) {
                var j=e.getAsJsonObject();String code=j.get("code").getAsString();
                nodes.add(new Node(code,null,"SECTION",code,j.get("nameRu").getAsString(),j.get("nameVi").getAsString(),"","",false,true,null,null,j.get("sourceUrl").getAsString()));
            }
            if(nodes.size()!=21||!nodes.stream().map(Node::code).toList().equals(SECTIONS))throw new IllegalStateException("Invalid root sections.");
            return nodes;
        }
    }
    public synchronized void importVersion(Version version,List<Node> imported,boolean confirmed) throws Exception {
        if(!confirmed||version==null||version.label()==null||version.label().isBlank()||version.label().length()>120||version.validFrom()==null)
            throw new IllegalArgumentException("A dated catalog version and explicit confirmation are required.");
        requireSource(version.sourceUrl());
        if(imported==null||imported.size()>100_000)throw new IllegalArgumentException("Invalid catalog size.");
        Map<String,Node> all=new LinkedHashMap<>();for(var n:roots())all.put(n.code(),n);
        Set<String> provided=new HashSet<>();
        for(var n:imported) {
            if(n==null||n.code()==null||!provided.add(n.code()))throw new IllegalArgumentException("Duplicate or missing code.");
            requireSource(n.sourceUrl());
            if(!SECTIONS.contains(n.section())||n.nameRu()==null||n.nameRu().isBlank()||n.nameVi()==null||n.nameRu().length()>5000||n.nameVi().length()>5000)
                throw new IllegalArgumentException("Invalid catalog names or section.");
            if("SECTION".equals(n.type())) {
                if(!SECTIONS.contains(n.code())||!n.code().equals(n.section())||n.parentCode()!=null||n.leaf())throw new IllegalArgumentException("Invalid section.");
            } else {
                if(!Set.of("CHAPTER","HEADING","SUBHEADING","DETAIL","LEAF").contains(n.type())||!n.code().matches("[0-9]{2,10}")||n.parentCode()==null||n.validFrom()==null)
                    throw new IllegalArgumentException("Invalid code, parent or effective date.");
                if(n.leaf()!= "LEAF".equals(n.type())||(n.leaf()&&n.code().length()!=10))throw new IllegalArgumentException("Invalid terminal code.");
                if("CHAPTER".equals(n.type())&&n.code().length()!=2||"HEADING".equals(n.type())&&n.code().length()!=4||"SUBHEADING".equals(n.type())&&n.code().length()!=6)
                    throw new IllegalArgumentException("Invalid classification level.");
                int chapter=Integer.parseInt(n.code().substring(0,2));int[] range=RANGES[SECTIONS.indexOf(n.section())];
                if(chapter==77&&n.active()||chapter<range[0]||chapter>range[1])throw new IllegalArgumentException("Invalid chapter for section.");
            }
            if(n.validTo()!=null&&(n.validFrom()==null||n.validTo().isBefore(n.validFrom())))throw new IllegalArgumentException("Invalid effective interval.");
            all.put(n.code(),n);
        }
        for(var n:all.values()) {
            Set<String> visited=new HashSet<>();Node cursor=n;
            while(cursor.parentCode()!=null) {
                if(!visited.add(cursor.code()))throw new IllegalArgumentException("Catalog cycle.");
                Node parent=all.get(cursor.parentCode());
                if(parent==null||parent.leaf()||!n.section().equals(parent.section()))throw new IllegalArgumentException("Invalid parent.");
                if(!"SECTION".equals(parent.type())&&(!cursor.code().startsWith(parent.code())||cursor.code().length()<=parent.code().length()))throw new IllegalArgumentException("Invalid numeric ancestry.");
                cursor=parent;
            }
        }
        snapshot();
        store(version,new ArrayList<>(all.values()),true);
    }
    public synchronized Path snapshot() throws Exception {
        Path directory=database.getParent().resolve("tnved").resolve("backups");Files.createDirectories(directory);
        Path destination=directory.resolve("catalog-"+UUID.randomUUID()+".sqlite");
        try(var c=connect();var statement=c.createStatement()) {
            statement.execute("VACUUM INTO '"+destination.toString().replace("'","''")+"'");
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+destination);var statement=c.createStatement();var rows=statement.executeQuery("PRAGMA integrity_check")) {
            if(!rows.next()||!"ok".equals(rows.getString(1)))throw new SQLException("Invalid TN VED snapshot.");
        }
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(destination));
        Files.writeString(Path.of(destination+".sha256"),HexFormat.of().formatHex(digest)+"\n");
        return destination;
    }
    private void store(Version version,List<Node> nodes,boolean verified) throws Exception {
        try(var c=connect()) {
            c.setAutoCommit(false);
            try {
                long versionId;
                try(var p=c.prepareStatement("INSERT INTO tnved_versions(label,source_url,valid_from,verified) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                    p.setString(1,version.label());p.setString(2,version.sourceUrl());p.setString(3,string(version.validFrom()));p.setInt(4,verified?1:0);p.executeUpdate();
                    try(var r=p.getGeneratedKeys()){r.next();versionId=r.getLong(1);}
                }
                Map<String,Long> ids=new HashMap<>();List<Node> remaining=new ArrayList<>(nodes);
                while(!remaining.isEmpty()) {
                    int before=remaining.size();
                    for(var it=remaining.iterator();it.hasNext();) {
                        Node n=it.next();if(n.parentCode()!=null&&!ids.containsKey(n.parentCode()))continue;
                        try(var p=c.prepareStatement("INSERT INTO tnved_nodes(version_id,parent_id,node_type,code,section_code,name_ru,name_vi,description_ru,notes,is_leaf,is_active,valid_from,valid_to,source_url,source_version,search_text) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                            p.setLong(1,versionId);if(n.parentCode()==null)p.setNull(2,Types.INTEGER);else p.setLong(2,ids.get(n.parentCode()));
                            p.setString(3,n.type());p.setString(4,n.code());p.setString(5,n.section());p.setString(6,n.nameRu());p.setString(7,n.nameVi());p.setString(8,n.descriptionRu());p.setString(9,n.notes());p.setInt(10,n.leaf()?1:0);p.setInt(11,n.active()?1:0);p.setString(12,string(n.validFrom()));p.setString(13,string(n.validTo()));p.setString(14,n.sourceUrl());p.setString(15,version.label());p.setString(16,normalize(n.code()+" "+n.nameRu()+" "+n.nameVi()));p.executeUpdate();
                            try(var r=p.getGeneratedKeys()){r.next();ids.put(n.code(),r.getLong(1));}
                        }
                        it.remove();
                    }
                    if(before==remaining.size())throw new IllegalArgumentException("Unresolved catalog ancestry.");
                }
                try(var s=c.createStatement()){s.executeUpdate("UPDATE tnved_versions SET active=0 WHERE active=1");}
                try(var p=c.prepareStatement("UPDATE tnved_versions SET active=1 WHERE id=?")){p.setLong(1,versionId);p.executeUpdate();}
                c.commit();
            } catch(Exception error){c.rollback();throw error;}
        }
    }
    public String activeVersion() throws SQLException {
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT label FROM tnved_versions WHERE active=1")){return r.next()?r.getString(1):"";}
    }
    public List<Node> children(String parentCode) throws SQLException {
        String where=parentCode==null?"n.parent_id IS NULL":"n.parent_id=(SELECT id FROM tnved_nodes WHERE version_id=v.id AND code=?)";
        try(var c=connect();var p=c.prepareStatement(select()+" WHERE v.active=1 AND "+where+" ORDER BY n.id")) {
            if(parentCode!=null)p.setString(1,parentCode);try(var r=p.executeQuery()){return read(r);}
        }
    }
    public List<Node> search(String query,int offset,int limit,LocalDate date) throws SQLException {
        if(offset<0||limit<1||limit>100)throw new IllegalArgumentException("Invalid page.");
        String dates=date==null?"":" AND n.is_active=1 AND (n.valid_from IS NULL OR n.valid_from<=?) AND (n.valid_to IS NULL OR n.valid_to>=?)";
        try(var c=connect();var p=c.prepareStatement(select()+" WHERE v.active=1 AND n.search_text LIKE ? ESCAPE '\\'"+dates+" ORDER BY n.code LIMIT ? OFFSET ?")) {
            String q=normalize(query==null?"":query).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");int i=1;p.setString(i++,"%"+q+"%");
            if(date!=null){p.setString(i++,date.toString());p.setString(i++,date.toString());}p.setInt(i++,limit);p.setInt(i,offset);try(var r=p.executeQuery()){return read(r);}
        }
    }
    public boolean isAssignable(String code,LocalDate date) throws SQLException {
        if(code==null||date==null)return false;
        try(var c=connect();var p=c.prepareStatement("SELECT n.id,n.parent_id,n.is_leaf,n.is_active,n.valid_from,n.valid_to FROM tnved_nodes n JOIN tnved_versions v ON n.version_id=v.id WHERE v.active=1 AND v.verified=1 AND v.valid_from<=? AND n.code=?")) {
            p.setString(1,date.toString());p.setString(2,code);try(var r=p.executeQuery()) {
                if(!r.next()||!r.getBoolean("is_leaf")||r.getString("valid_from")==null)return false;
                long id=r.getLong("id");
                while(id>0) {
                    try(var parent=c.prepareStatement("SELECT parent_id,is_active,valid_from,valid_to FROM tnved_nodes WHERE id=?")) {
                        parent.setLong(1,id);try(var row=parent.executeQuery()) {
                            if(!row.next()||!row.getBoolean("is_active"))return false;
                            LocalDate from=local(row.getString("valid_from")),to=local(row.getString("valid_to"));
                            if(from!=null&&date.isBefore(from)||to!=null&&date.isAfter(to))return false;
                            id=row.getLong("parent_id");
                        }
                    }
                }
                return true;
            }
        }
    }
    private static String select(){return "SELECT n.*,p.code AS parent_code FROM tnved_nodes n JOIN tnved_versions v ON n.version_id=v.id LEFT JOIN tnved_nodes p ON p.id=n.parent_id";}
    private static List<Node> read(ResultSet r)throws SQLException {
        List<Node> nodes=new ArrayList<>();while(r.next())nodes.add(new Node(r.getString("code"),r.getString("parent_code"),r.getString("node_type"),r.getString("section_code"),r.getString("name_ru"),r.getString("name_vi"),r.getString("description_ru"),r.getString("notes"),r.getBoolean("is_leaf"),r.getBoolean("is_active"),local(r.getString("valid_from")),local(r.getString("valid_to")),r.getString("source_url")));return nodes;
    }
    static String normalize(String text){return Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('ё','е').replace('đ','d'),Normalizer.Form.NFD).replaceAll("\\p{M}+","").strip();}
    static void requireSource(String source){try{var u=java.net.URI.create(source);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException();}catch(RuntimeException e){throw new IllegalArgumentException("A valid HTTPS source citation is required.");}}
    private static LocalDate local(String value){return value==null?null:LocalDate.parse(value);}
    private static String string(LocalDate value){return value==null?null:value.toString();}
}

package com.kevindeyne.datascrambler;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Stream;

@ShellComponent
public class ScrambleCommands {

    public static final String NONE = "_NONE";
    public static final String NO_DB = "Please specify a database with the --database option. The following databases are found: ";
    public static final String NO_CONFIG_FILE = "No config file was found. Please adjust the new file created in root. ";

    public static final String CONFIG_JSON = "config.json";

    @ShellMethod("Builds a sample config file")
    public String config() {
        doesFileExist();
        return NO_CONFIG_FILE;
    }

    private void buildSampleConfigFile() {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(CONFIG_JSON, "UTF-8");
            String json = JsonWriter.string()
                    .object()
                    .value("type", "MySQL")
                    .value("host", "localhost")
                    .value("port", 3306)
                    .value("username", "datareader")
                    .value("password", "d@tar3ader")
                    .end()
                    .done();
            writer.println(json);

            throw new RuntimeException(NO_CONFIG_FILE);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Impossible to create a config file, check if directory is not read-only.");
        } catch (Exception e) {
            throw new RuntimeException("Could not read file: " + e.getMessage());
        } finally {
            if(writer != null){
                writer.close();
            }
        }
    }

    private void doesFileExist() {
        File f = new File(CONFIG_JSON);
        if(f.exists() && !f.isDirectory()) {
            System.out.println("Config file found");
        } else {
            buildSampleConfigFile();
        }
    }

    private String loadConfig(){
        StringBuilder sb = new StringBuilder();
        try (Stream<String> stream = Files.lines(Paths.get(CONFIG_JSON))) {
            stream.forEach(s -> sb.append(s));
        } catch (IOException e) {
            return "Could not read config file: " + e.getMessage();
        }
        return sb.toString();
    }

    private JsonObject parseConfig(String jsonString) {
        try {
            return JsonParser.object().from(jsonString);
        } catch (JsonParserException e) {
            throw new RuntimeException("Could not read config file: " + e.getMessage());
        }
    }

    @ShellMethod("Connects to a source database and downloads a scrambled version of said source.")
    public String download(@ShellOption(defaultValue = NONE) String database) {
        System.out.println("Checking if config file exists yet");

        try {
            doesFileExist();

            String config = loadConfig();
            JsonObject obj = parseConfig(config);

            String host = obj.getString("host");
            String username = obj.getString("username");
            String password = obj.getString("password");
            String type = obj.getString("type");
            int port = obj.getInt("port");

            System.out.println("   Host: " + host);
            System.out.println("   Port: " + port);
            System.out.println("   Username: " + username);
        } catch (RuntimeException e) {
            return e.getMessage();
        }

        if (noDatabaseParameterProvided(database))
            return NO_DB;

        return "HELLO WORLD";
    }

    @ShellMethod("Uploads a scrambled version file to a destination database")
    public String upload(@ShellOption(defaultValue = NONE) String database) {
        if (noDatabaseParameterProvided(database))
            return "Please specify a database with the --database option. The following databases are found: inburgering, inburgering-platform, inburgering, inburgering-platform, inburgering, inburgering-platform, inburgering, inburgering-platform";


        return "HELLO WORLD 2 ";
    }

    private boolean noDatabaseParameterProvided(@ShellOption(defaultValue = NONE) String database) {
        if(NONE.equals(database)) {
            return true;
        }
        return false;
    }
}
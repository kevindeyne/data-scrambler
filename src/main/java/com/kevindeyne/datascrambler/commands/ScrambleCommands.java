package com.kevindeyne.datascrambler.commands;

import com.kevindeyne.datascrambler.domain.Config;
import com.kevindeyne.datascrambler.domain.MConnection;
import com.kevindeyne.datascrambler.helper.Copying;
import com.kevindeyne.datascrambler.helper.PrintCmds;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import static com.kevindeyne.datascrambler.helper.ConfigLoading.*;

@ShellComponent
public class ScrambleCommands {

    private static final String NONE = "_NONE";
    private static final String NO_DB = "Please specify a database with the --database option. The following databases are found: ";

    @ShellMethod("Builds a sample config file")
    public String config() {
        try {
            doesFileExist();
            return NO_CONFIG_FILE;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    @ShellMethod("Connects to a source database and downloads a scrambled version of said source.")
    public String download(@ShellOption(defaultValue = NONE) String database) {
        try {
            Config obj = getConfigFile();
            obj.printProps();
            MConnection con = obj.buildConnection();
            if (noDatabaseParameterProvided(database))
                return PrintCmds.green(NO_DB + con.getFoundDatabases());
            Copying.downloadDatabase(con);
        } catch (RuntimeException e) {
            return e.getMessage();
        }

        return PrintCmds.green("Download complete. Check the files in the /downloaded map. Run upload.");
    }

    @ShellMethod("Uploads a scrambled version file to a destination database")
    public String upload(@ShellOption(defaultValue = NONE) String database) {
        if (noDatabaseParameterProvided(database))
            return PrintCmds.green(NO_DB + " TODO");

        return "UPLOAD IS STILL TO DO";
    }

    private boolean noDatabaseParameterProvided(@ShellOption(defaultValue = NONE) String database) {
        return NONE.equals(database);
    }
}
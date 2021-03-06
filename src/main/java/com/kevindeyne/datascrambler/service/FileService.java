package com.kevindeyne.datascrambler.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.kevindeyne.datascrambler.domain.distributionmodel.DistributionModel;
import com.kevindeyne.datascrambler.exceptions.ConfigFileException;
import com.kevindeyne.datascrambler.shell.ShellHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Service
public class FileService {

    private static final String UTF_8 = "UTF-8";
    private static final Gson GSON = new GsonBuilder().create();

    @Lazy
    @Autowired
    private ShellHelper shellHelper;

    public boolean doesFileExist(String file, String successMessage, String failureMessage) {
        File f = new File(file);
        if (f.exists() && !f.isDirectory()) {
            shellHelper.printSuccess(successMessage);
            return true;
        } else {
            shellHelper.printWarning(failureMessage);
            return false;
        }
    }

    public String loadFile(String file) throws ConfigFileException {
        try (Stream<String> stream = Files.lines(Paths.get(file))) {
            StringBuilder sb = new StringBuilder();
            stream.forEach(sb::append);
            return sb.toString();
        } catch (IOException e) {
            throw new ConfigFileException("Could not read config file: " + e.getMessage(), e);
        }
    }

    public boolean deleteFile(String file) {
        return Paths.get(file).toFile().delete();
    }

    public void writeToFile(JsonObject configObj, String file) throws ConfigFileException {
        try (PrintWriter writer = new PrintWriter(file, UTF_8)) {
            writer.println(GSON.toJson(configObj));
        } catch (FileNotFoundException e) {
            throw new ConfigFileException("Impossible to create a config file, check if directory is not read-only.", e);
        } catch (Exception e) {
            throw new ConfigFileException("Could not read file: " + e.getMessage(), e);
        }
    }

    public DistributionModel loadModel(String file) throws ConfigFileException {
        String rawJson = loadFile(file);
        return GSON.fromJson(rawJson, DistributionModel.class);
    }
}

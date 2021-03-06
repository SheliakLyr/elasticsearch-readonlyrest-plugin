/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.commons;

import cz.seznam.euphoria.shaded.guava.com.google.common.util.concurrent.FutureCallback;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.ESVersion;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Observable;

import static tech.beshu.ror.commons.Constants.SETTINGS_YAML_FILE;

abstract public class SettingsObservable extends Observable {
  public static final String SETTINGS_NOT_FOUND_MESSAGE = "no settings found in index";

  private boolean printedInfo = false;

  protected SettingsForStorage current;

  public void updateSettings(SettingsForStorage newSettings) {
    this.current = newSettings;
    setChanged();
    notifyObservers();
  }

  public SettingsForStorage getCurrent() {
    return current;
  }

  // protected abstract void writeToIndex();

  protected abstract boolean isClusterReady();

  public SettingsForStorage getFromFileWithFallbackToES() {
    LoggerShim logger = getLogger();

    String filePath = Constants.makeAbsolutePath("config" + File.separator);

    if (!filePath.endsWith(File.separator)) {
      filePath += File.separator;
    }

    final String esFilePath = filePath + "elasticsearch.yml";

    filePath += SETTINGS_YAML_FILE;

    String finalFilePath = filePath;

    final SettingsForStorage[] s4s = new SettingsForStorage[1];
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      try {
        String slurped = new String(Files.readAllBytes(Paths.get(finalFilePath)));
        s4s[0] = new SettingsForStorage(slurped);
        logger.info("Loaded good settings from " + finalFilePath);
      } catch (Throwable t) {
        logger.info(
          "Could not find settings in "
            + finalFilePath + ", falling back to elasticsearch.yml (" + t.getMessage() + ")");
        try {
          String slurped = new String(Files.readAllBytes(Paths.get(esFilePath)));
          s4s[0] = new SettingsForStorage(slurped);
          logger.info("Loaded good settings from " + esFilePath);
        } catch (IOException e) {
          throw new SettingsMalformedException("Cannot even read elasticsearch.yml, giving up", e);
        }
      }
      return null;
    });
    return s4s[0];
  }

  protected abstract LoggerShim getLogger();

  protected abstract SettingsForStorage getFromIndex();

  protected abstract void writeToIndex(SettingsForStorage s4s, FutureCallback f);


  public void refreshFromIndex() {
    try {
      getLogger().debug("[CLUSTERWIDE SETTINGS] checking index..");
      SettingsForStorage fromIndex = getFromIndex();
      if(!fromIndex.asRawYAML().equals(current.asRawYAML())) {
        updateSettings(fromIndex);
        getLogger().info("[CLUSTERWIDE SETTINGS] good settings found in index, overriding local YAML file");
      }
    } catch (Throwable t) {
      if ( SETTINGS_NOT_FOUND_MESSAGE.equals(t.getMessage())) {
        if(!printedInfo) {
          getLogger().info("[CLUSTERWIDE SETTINGS] index settings not found. Will keep on using the local YAML file. " +
                             "Learn more about clusterwide settings at https://readonlyrest.com/pro.html ");
        }
        printedInfo = true;
      }
      else {
        t.printStackTrace();
      }
    }
  }

  public void forceRefresh() {
    setChanged();
    notifyObservers();
  }

  public void refreshFromStringAndPersist(SettingsForStorage newSettings, FutureCallback fut) {
    SettingsForStorage oldSettings = current;
    current = newSettings;
    try {
      forceRefresh();
      writeToIndex(newSettings, fut);
    } catch (Throwable t) {
      current = oldSettings;
      throw new SettingsMalformedException(t.getMessage());
    }
  }

  public void pollForIndex(ESContext context) {
    if (context.getVersion().before(ESVersion.V_5_0_0)) {
      return;
    }
    new SettingsPoller(this, context, 1, 5, true).poll();
  }

}

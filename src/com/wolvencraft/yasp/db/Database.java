/*
 * Database.java
 * 
 * Statistics
 * Copyright (C) 2013 bitWolfy <http://www.wolvencraft.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.wolvencraft.yasp.db;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;

import com.wolvencraft.yasp.Settings;
import com.wolvencraft.yasp.Statistics;
import com.wolvencraft.yasp.Settings.LocalConfiguration;
import com.wolvencraft.yasp.db.Query.QueryResult;
import com.wolvencraft.yasp.db.tables.Normal.SettingsTable;
import com.wolvencraft.yasp.exceptions.DatabaseConnectionException;
import com.wolvencraft.yasp.exceptions.RuntimeSQLException;
import com.wolvencraft.yasp.util.Message;

/**
 * Represents a running database instance.<br />
 * There can only be one instance running at any given time.
 * @author bitWolfy
 *
 */
public class Database {
    
    private static Database instance = null;
    private static Connection connection = null;
    
    /**
     * Default constructor. Connects to the remote database, performs patches if necessary, and holds to the DB info.<br />
     * @throws DatabaseConnectionException Thrown if the plugin could not connect to the database
     */
    public Database() throws DatabaseConnectionException {
        if (instance != null) throw new DatabaseConnectionException("Attempted to establish a duplicate connection with a remote database");
        
        try { Class.forName("com.mysql.jdbc.Driver"); }
        catch (ClassNotFoundException ex) { throw new DatabaseConnectionException("MySQL driver was not found!"); }
        
        instance = this;
        
        connect();
        
        try { if (connection.getAutoCommit()) connection.setAutoCommit(false); }
        catch (Throwable t) { throw new RuntimeSQLException("Could not set AutoCommit to false. Cause: " + t, t); }
        
        if(!runPatcher(false)) Message.log("Target database is up to date");
        Statistics.setPaused(false);
    }
    
    /**
     * Connects to the remote database according to the data stored in the configuration
     * @throws DatabaseConnectionException Thrown if an error occurs while connecting to the database
     */
    private static void connect() throws DatabaseConnectionException {
        try {
            connection = DriverManager.getConnection(
                LocalConfiguration.DBConnect.asString(),
                LocalConfiguration.DBUser.asString(),
                LocalConfiguration.DBPass.asString()
            );
        } catch (SQLException e) { throw new DatabaseConnectionException(e); }
    }
    
    /**
     * Patches the remote database to the latest version.<br />
     * This method will run in the <b>main server thread</b> and therefore will freeze the server until the patch is complete.
     * @param force <b>true</b> to drop all data in the database and start anew.
     * @return <b>true</b> if a patch was applied, <b>false</b> if it was not.
     * @throws DatabaseConnectionException Thrown if the plugin is unable to patch the remote database
     */
    public static boolean runPatcher(boolean force) throws DatabaseConnectionException {
        int databaseVersion;
        if(force) { databaseVersion = 1; }
        else { databaseVersion = Settings.RemoteConfiguration.DatabaseVersion.asInteger(); }
        int latestPatchVersion = databaseVersion;
        
        while (instance.getClass().getClassLoader().getResourceAsStream("SQLPatches/" + (latestPatchVersion + 1) + ".yasp.sql") != null) {
            latestPatchVersion++;
        }
        
        if(databaseVersion >= latestPatchVersion) { return true; }
        
        Message.debug("Current version: " + databaseVersion + ", latest version: " + latestPatchVersion);
        
        databaseVersion++;
        
        ScriptRunner scriptRunner = new ScriptRunner(connection);
        Message.log("+-------] Database Patcher [-------+");
        for(; databaseVersion <= latestPatchVersion; databaseVersion++) {
            Message.log("|       Applying patch " + databaseVersion + " / " + latestPatchVersion + "       |");
            executePatch(scriptRunner, databaseVersion + ".yasp");
            Settings.RemoteConfiguration.DatabaseVersion.update(databaseVersion);
        }
        Message.log("+----------------------------------+");
        
        return true;
    }
    
    /**
     * Applies the patch to the remote database
     * @param patchId Unique ID of the desired patch, i.e. <code>1.vault</code>.
     * @throws DatabaseConnectionException Thrown if the plugin is unable to patch the remote database
     * @return <b>true</b> if a patch was applied, <b>false</b> if it was not.
     */
    public static boolean executePatch(String patchId) throws DatabaseConnectionException {
        return executePatch(new ScriptRunner(connection), patchId);
    }
    
    /**
     * Applies the patch to the remote database
     * @param scriptRunner Script runner instance that executes the patch
     * @param patchId Unique ID of the desired patch, i.e. <code>1.vault</code>.
     * @throws DatabaseConnectionException Thrown if the plugin is unable to patch the remote database
     * @return <b>true</b> if a patch was applied, <b>false</b> if it was not.
     */
    public static boolean executePatch(ScriptRunner scriptRunner, String patchId) throws DatabaseConnectionException {
        InputStream is = instance.getClass().getClassLoader().getResourceAsStream("SQLPatches/" + patchId + ".sql");
        if (is == null) return false;
        Message.log(Level.FINE, "Executing database patch: " + patchId + ".sql");
        try {scriptRunner.runScript(new InputStreamReader(is)); }
        catch (RuntimeSQLException e) { throw new DatabaseConnectionException("An error occured while executing database patch: " + patchId + ".sql", e); }
        finally {
            if(!Query.table(SettingsTable.TableName).column("patched").exists()) {
                Query.table(SettingsTable.TableName).value("patched", 1).insert();
            }
            Query.table(SettingsTable.TableName).value("patched", 1).update();
        }
        return true;
    }
    
    /**
     * Attempts to reconnect to the remote server
     * @return <b>true</b> if the connection was present, or reconnect is successful. <b>false</b> otherwise.
     */
    public static boolean reconnect() {
        try {
            if (connection.isValid(10)) {
                Message.log("Connection is still present. Malformed query detected.");
                return true;
            } else {
                Message.log(Level.WARNING, "Attempting to re-connect to the database");
                try {
                    connect();
                    Message.log("Connection re-established. No data is lost.");
                    return true;
                } catch (DatabaseConnectionException e) {
                    Message.log(Level.SEVERE, "Failed to re-connect to the database. Data is being stored locally.");
                    if (LocalConfiguration.Debug.asBoolean()) e.printStackTrace();
                    return false;
                }
            }
        } catch (SQLException e) {
            if (LocalConfiguration.Debug.asBoolean()) e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Pushes data to the remote database.<br />
     * This is a raw method and should never be used by itself. Use the <b>QueryUtils</b> wrapper for more options 
     * and proper error handling. This method is not to be used for regular commits to the database.
     * @param query SQL query
     * @return <b>true</b> if the sync is successful, <b>false</b> otherwise
     */
    public static boolean executeUpdate(String query) {
        int rowsChanged = 0;
        Statement statement = null;
        try {
            statement = connection.createStatement();
            rowsChanged = statement.executeUpdate(query);
            statement.close();
            connection.commit();
        } catch (SQLException e) {
            Message.log(Level.WARNING,
                    "Error retrieving data from the database",
                    e.getMessage(),
                    query
            );
            if(LocalConfiguration.Debug.asBoolean()) e.printStackTrace();
            return reconnect();
        } finally {
            if (statement != null) {
                try { statement.close(); }
                catch (SQLException e) { Message.log(Level.SEVERE, "Error closing database connection"); }
            }
        }
        return rowsChanged > 0;
    }
    
    /**
     * Returns the data from the remote server according to the SQL query.<br />
     * This is a raw method and should never be used by itself. Use the <b>QueryUtils</b> wrapper for more options 
     * and proper error handling. This method is not to be used for regular commits to the database.
     * @param query SQL query
     * @return Data from the remote database
     */
    public static List<QueryResult> executeQuery(String query) {
        List<QueryResult> colData = new ArrayList<QueryResult>();

        Statement statement = null;
        ResultSet rs = null;
        try {
            statement = connection.createStatement();
            rs = statement.executeQuery(query);
            while (rs.next()) {
                HashMap<String, String> rowToAdd = new HashMap<String, String>();
                for (int x = 1; x <= rs.getMetaData().getColumnCount(); ++x) {
                    rowToAdd.put(rs.getMetaData().getColumnName(x), rs.getString(x));
                }
                colData.add(Query.toQueryResult(rowToAdd));
            }
        } catch (SQLException e) {
            Message.log(Level.WARNING,
                    "Error retrieving data from the database",
                    e.getMessage(),
                    query
            );
            if(LocalConfiguration.Debug.asBoolean()) e.printStackTrace();
            reconnect();
            return new ArrayList<QueryResult>();
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {}
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) { Message.log(Level.SEVERE, "Error closing database connection"); }
            }
        }
        return colData;
    }
    
    /**
     * Cleans up the leftover database and connection instances to prevent memory leaks.
     */
    public static void cleanup() {
        connection = null;
        instance = null;
    }
}

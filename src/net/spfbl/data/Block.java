/*
 * This file is part of SPFBL.
 * 
 * SPFBL is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * SPFBL is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with SPFBL.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.spfbl.data;

import net.spfbl.core.Reverse;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.spfbl.core.Client;
import net.spfbl.core.Core;
import net.spfbl.core.Peer;
import net.spfbl.core.ProcessException;
import net.spfbl.core.Server;
import net.spfbl.core.User;
import net.spfbl.spf.SPF;
import net.spfbl.whois.Domain;
import net.spfbl.whois.Subnet;
import net.spfbl.whois.SubnetIPv4;
import net.spfbl.whois.SubnetIPv6;
import org.apache.commons.lang3.SerializationUtils;

/**
 * Representa a lista de bloqueio do sistema.
 * 
 * @author Leandro Carlos Rodrigues <leandro@spfbl.net>
 */
public class Block {
    
    /**
     * Flag que indica se o cache foi modificado.
     */
    private static boolean CHANGED = false;

    /**
     * Conjunto de remetentes bloqueados.
     */
    private static class SET {
        
        private static final HashSet<String> SET = new HashSet<String>();
        
        public static synchronized boolean isEmpty() {
            return SET.isEmpty();
        }
        
        public static synchronized void clear() {
            SET.clear();
        }
        
        public static synchronized TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            set.addAll(SET);
            return set;
        }
        
        private static synchronized boolean addExact(String token) {
            return SET.add(token);
        }
        
        private static synchronized boolean dropExact(String token) {
            return SET.remove(token);
        }
        
        public static boolean contains(String token) {
            return SET.contains(token);
        }
    }
    
    private static void logTrace(long time, String message) {
        Server.log(time, Core.Level.TRACE, "BLOCK", message, (String) null);
    }
    
    /**
     * Conjunto de critperios WHOIS para bloqueio.
     */
    private static class WHOIS {
        
        private static final HashMap<String,TreeSet<String>> MAP = new HashMap<String,TreeSet<String>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized void clear() {
            MAP.clear();
        }
        
        public static synchronized TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            for (String client : MAP.keySet()) {
                for (String whois : MAP.get(client)) {
                    if (client == null) {
                        set.add("WHOIS/" + whois);
                    } else {
                        set.add(client + ":WHOIS/" + whois);
                    }
                }
        }
            return set;
        }
        
        private static synchronized boolean dropExact(String token) {
            int index = token.indexOf('/');
            String whois = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                return false;
            } else {
                boolean removed = set.remove(whois);
                if (set.isEmpty()) {
                    MAP.remove(client);
                }
                return removed;
            }
        }
        
        private static synchronized boolean addExact(String token) {
            int index = token.indexOf('/');
            String whois = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                set = new TreeSet<String>();
                MAP.put(client, set);
            }
            return set.add(whois);
        }
        
        public static boolean contains(String client, String host) {
            if (host == null) {
                return false;
            } else {
                TreeSet<String> whoisSet = MAP.get(client);
                if (whoisSet == null) {
                    return false;
                } else {
                    return whoisSet.contains(host);
                }
            }
        }
        
//        private static String[] getArray(String client) {
//            TreeSet<String> set = MAP.get(client);
//            if (set == null) {
//                return null;
//            } else {
//                int size = set.size();
//                String[] array = new String[size];
//                return set.toArray(array);
//            }
//        }
        
        private static String get(
                Client client,
                Set<String> tokenSet,
                boolean autoBlock
        ) {
            if (tokenSet.isEmpty()) {
                return null;
            } else {
                long time = System.currentTimeMillis();
                TreeSet<String> subSet = new TreeSet<String>();
                TreeSet<String> whoisSet = MAP.get(null);
                if (whoisSet != null) {
                    subSet.addAll(whoisSet);
                }
                if (client != null && client.hasEmail()) {
                    whoisSet = MAP.get(client.getEmail());
                    if (whoisSet != null) {
                        for (String whois : whoisSet) {
                            subSet.add(client.getEmail() + ':' + whois);
                        }
                    }
                }
                if (subSet.isEmpty()) {
                    return null;
                } else {
                    for (String whois : subSet) {
                        try {
                            char signal = '=';
                            int indexValue = whois.indexOf(signal);
                            if (indexValue == -1) {
                                signal = '<';
                                indexValue = whois.indexOf(signal);
                                if (indexValue == -1) {
                                    signal = '>';
                                    indexValue = whois.indexOf(signal);
                                }
                            }
                            if (indexValue != -1) {
                                String user = null;
                                int indexUser = whois.indexOf(':');
                                if (indexUser > 0 && indexUser < indexValue) {
                                    user = whois.substring(0, indexUser);
                                }
                                String key = whois.substring(indexUser + 1, indexValue);
                                String criterion = whois.substring(indexValue + 1);
                                for (String token : tokenSet) {
                                    String value = null;
                                    if (Subnet.isValidIP(token)) {
                                        value = Subnet.getValue(token, key);
                                    } else if (!token.startsWith(".") && Domain.containsDomain(token)) {
                                        value = Domain.getValue(token, key);
                                    } else if (!token.startsWith(".") && Domain.containsDomain(token.substring(1))) {
                                        value = Domain.getValue(token, key);
                                    }
                                    if (value != null) {
                                        if (signal == '=') {
                                            if (criterion.equals(value)) {
                                                if (autoBlock && (token = addDomain(user, token)) != null) {
                                                    if (user == null) {
                                                        Server.logDebug("new BLOCK '" + token + "' added by 'WHOIS/" + whois + "'.");
                                                        Peer.sendBlockToAll(token);
                                                    } else {
                                                        Server.logDebug("new BLOCK '" + user + ":" + token + "' added by '" + user + ":WHOIS/" + whois + "'.");
                                                    }
                                                }
                                                logTrace(time, "WHOIS lookup for " + tokenSet + ".");
                                                return whois;
                                            }
                                        } else if (value.length() > 0) {
                                            int criterionInt = parseIntWHOIS(criterion);
                                            int valueInt = parseIntWHOIS(value);
                                            if (signal == '<' && valueInt < criterionInt) {
                                                if (autoBlock && (token = addDomain(user, token)) != null) {
                                                    if (user == null) {
                                                        Server.logDebug("new BLOCK '" + token + "' added by 'WHOIS/" + whois + "'.");
                                                        Peer.sendBlockToAll(token);
                                                    } else {
                                                        Server.logDebug("new BLOCK '" + user + ":" + token + "' added by '" + user + ":WHOIS/" + whois + "'.");
                                                    }
                                                }
                                                logTrace(time, "WHOIS lookup for " + tokenSet + ".");
                                                return whois;
                                            } else if (signal == '>' && valueInt > criterionInt) {
                                                if (autoBlock && (token = addDomain(user, token)) != null) {
                                                    if (user == null) {
                                                        Server.logDebug("new BLOCK '" + token + "' added by 'WHOIS/" + whois + "'.");
                                                        Peer.sendBlockToAll(token);
                                                    } else {
                                                        Server.logDebug("new BLOCK '" + user + ":" + token + "' added by '" + user + ":WHOIS/" + whois + "'.");
                                                    }
                                                }
                                                logTrace(time, "WHOIS lookup for " + tokenSet + ".");
                                                return whois;
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            Server.logError(ex);
                        }
                    }
                    logTrace(time, "WHOIS lookup for " + tokenSet + ".");
                    return null;
                }

            }
        }
    }
    
    /**
     * Conjunto de DNSBL para bloqueio de IP.
     */
    private static class DNSBL {
        
        private static final HashMap<String,TreeSet<String>> MAP = new HashMap<String,TreeSet<String>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized void clear() {
            MAP.clear();
        }
        
        public static synchronized TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            for (String client : MAP.keySet()) {
                for (String dnsbl : MAP.get(client)) {
                    if (client == null) {
                        set.add("DNSBL=" + dnsbl);
                    } else {
                        set.add(client + ":DNSBL=" + dnsbl);
                    }
                }
            }
            return set;
        }
        
        private static synchronized boolean dropExact(String token) {
            int index = token.indexOf('=');
            String dnsbl = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                return false;
            } else {
                boolean removed = set.remove(dnsbl);
                if (set.isEmpty()) {
                    MAP.remove(client);
                }
                return removed;
            }
        }
        
        private static synchronized boolean addExact(String token) {
            int index = token.indexOf('=');
            String dnsbl = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                set = new TreeSet<String>();
                MAP.put(client, set);
            }
            return set.add(dnsbl);
        }
        
        public static boolean contains(String client, String dnsbl) {
            if (dnsbl == null) {
                return false;
            } else {
                TreeSet<String> dnsblSet = MAP.get(client);
                if (dnsblSet == null) {
                    return false;
                } else {
                    return dnsblSet.contains(dnsbl);
                }
            }
        }
        
//        private static String[] getArray(String client) {
//            TreeSet<String> set = MAP.get(client);
//            if (set == null) {
//                return null;
//            } else {
//                int size = set.size();
//                String[] array = new String[size];
//                return set.toArray(array);
//            }
//        }
        
        private static String get(Client client, String ip) {
            if (ip == null) {
                return null;
            } else {
                long time = System.currentTimeMillis();
                TreeMap<String,TreeSet<String>> dnsblMap =
                        new TreeMap<String,TreeSet<String>>();
                TreeSet<String> registrySet = MAP.get(null);
                if (registrySet != null) {
                    for (String dnsbl : registrySet) {
                        int index = dnsbl.indexOf(';');
                        String server = dnsbl.substring(0, index);
                        String value = dnsbl.substring(index + 1);
                        TreeSet<String> dnsblSet = dnsblMap.get(server);
                        if (dnsblSet == null) {
                            dnsblSet = new TreeSet<String>();
                            dnsblMap.put(server, dnsblSet);
                        }
                        dnsblSet.add(value);
                    }
                }
                if (client != null && client.hasEmail()) {
                    registrySet = MAP.get(client.getEmail());
                    if (registrySet != null) {
                        for (String dnsbl : registrySet) {
                            int index = dnsbl.indexOf(';');
                            String server = dnsbl.substring(0, index);
                            String value = dnsbl.substring(index + 1);
                            TreeSet<String> dnsblSet = dnsblMap.get(server);
                            if (dnsblSet == null) {
                                dnsblSet = new TreeSet<String>();
                                dnsblMap.put(server, dnsblSet);
                            }
                            dnsblSet.add(value);
                        }
                    }
                }
                String result = null;
                for (String server : dnsblMap.keySet()) {
                    TreeSet<String> valueSet = dnsblMap.get(server);
                    String listed = Reverse.getListed(ip, server, valueSet);
                    if (listed != null) {
                        Server.logDebug("IP " + ip + " is listed in '" + server + ";" + listed + "'.");
                        result = server + ";" + listed;
                        break;
                    }
                }
                logTrace(time, "DNSBL lookup for '" + ip + "'.");
                return result;
            }
        }
    }
    
    /**
     * Conjunto de REGEX para bloqueio.
     */
    private static class REGEX {
        
        private static final HashMap<String,ArrayList<Pattern>> MAP = new HashMap<String,ArrayList<Pattern>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized void clear() {
            MAP.clear();
        }
        
        public static synchronized TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            for (String client : MAP.keySet()) {
                for (Pattern pattern : MAP.get(client)) {
                    if (client == null) {
                        set.add("REGEX=" + pattern);
                    } else {
                        set.add(client + ":REGEX=" + pattern);
                    }
                }
            }
            return set;
        }
        
        private static synchronized boolean dropExact(String token) {
            int index = token.indexOf('=');
            String regex = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            ArrayList<Pattern> list = MAP.get(client);
            if (list == null) {
                return false;
            } else {
                for (index = 0; index < list.size(); index++) {
                    Pattern pattern = list.get(index);
                    if (regex.equals(pattern.pattern())) {
                        list.remove(index);
                        if (list.isEmpty()) {
                            MAP.remove(client);
                        }
                        return true;
                    }
                }
                return false;
            }
        }
        
        private static synchronized boolean addExact(String token) {
            int index = token.indexOf('=');
            String regex = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            ArrayList<Pattern> list = MAP.get(client);
            if (list == null) {
                list = new ArrayList<Pattern>();
                MAP.put(client, list);
            }
            for (index = 0; index < list.size(); index++) {
                Pattern pattern = list.get(index);
                if (regex.equals(pattern.pattern())) {
                    return false;
                }
            }
            Pattern pattern = Pattern.compile(regex);
            list.add(pattern);
            return true;
        }
        
        public static boolean contains(String client, String regex) {
            if (regex == null) {
                return false;
            } else {
                ArrayList<Pattern> patternList = MAP.get(client);
                if (patternList == null) {
                    return false;
                } else {
                    for (Pattern pattern : patternList) {
                        if (regex.equals(pattern.pattern())) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }
        
//        private static Pattern[] getArray(String client) {
//            ArrayList<Pattern> patternList = MAP.get(client);
//            if (patternList == null) {
//                return null;
//            } else {
//                int size = patternList.size();
//                Pattern[] array = new Pattern[size];
//                return patternList.toArray(array);
//            }
//        }
        
        private static String get(
                Client client,
                Collection<String> tokenList,
                boolean autoBlock
                ) throws ProcessException {
            if (tokenList.isEmpty()) {
                return null;
            } else {
                long time = System.currentTimeMillis();
                String result = null;
                ArrayList<Pattern> patternList = MAP.get(null);
                if (patternList != null) {
                    for (Object object : patternList.toArray()) {
                        Pattern pattern = (Pattern) object;
                        for (String token : tokenList) {
                            if (token.contains("@") == pattern.pattern().contains("@")) {
                                Matcher matcher = pattern.matcher(token);
                                if (matcher.matches()) {
                                    String regex = "REGEX=" + pattern.pattern();
                                    if (autoBlock && Block.addExact(token)) {
                                        Server.logDebug("new BLOCK '" + token + "' added by '" + regex + "'.");
                                        if (client == null) {
                                            Peer.sendBlockToAll(token);
                                        }
                                    }
                                    result = regex;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (result == null && client != null && client.hasEmail()) {
                    patternList = MAP.get(client.getEmail());
                    if (patternList != null) {
                        for (Object object : patternList.toArray()) {
                            Pattern pattern = (Pattern) object;
                            for (String token : tokenList) {
                                if (token.contains("@") == pattern.pattern().contains("@")) {
                                    Matcher matcher = pattern.matcher(token);
                                    if (matcher.matches()) {
                                        String regex = "REGEX=" + pattern.pattern();
                                        token = client + ":" + token;
                                        if (autoBlock && addExact(token)) {
                                            Server.logDebug("new BLOCK '" + token + "' added by '" + client + ":" + regex + "'.");
                                        }
                                        result = client + ":" + regex;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                logTrace(time, "REGEX lookup for " + tokenList + ".");
                return result;
            }
        }
    }
    
    /**
     * Representa o conjunto de blocos IP bloqueados.
     */
    private static class CIDR {
        
        private static final HashMap<String,TreeSet<String>> MAP = new HashMap<String,TreeSet<String>>();
        
        public static synchronized boolean isEmpty() {
            return MAP.isEmpty();
        }
        
        public static synchronized void clear() {
            MAP.clear();
        }
        
        public static synchronized TreeSet<String> getExtended() {
            TreeSet<String> returnSet = new TreeSet<String>();
            TreeSet<String> cidrSet = MAP.get(null);
            if (cidrSet != null) {
                returnSet.addAll(cidrSet);
            }
            return returnSet;
        }
        
        public static synchronized TreeSet<String> getAll() {
            TreeSet<String> set = new TreeSet<String>();
            for (String client : MAP.keySet()) {
                for (String cidr : MAP.get(client)) {
                    if (cidr.contains(":")) {
                        cidr = SubnetIPv6.normalizeCIDRv6(cidr);
                    } else {
                        cidr = SubnetIPv4.normalizeCIDRv4(cidr);
                    }
                    if (client == null) {
                        set.add("CIDR=" + cidr);
                    } else {
                        set.add(client + ":CIDR=" + cidr);
                    }
                }
            }
            return set;
        }
        
        private static boolean split(String cidr) {
            if (CIDR.dropExact(cidr)) {
                cidr = cidr.substring(5);
                byte mask = Subnet.getMask(cidr);
                String first = Subnet.getFirstIP(cidr);
                String last = Subnet.getLastIP(cidr);
                int max = SubnetIPv4.isValidIPv4(first) ? 32 : 64;
                if (mask < max) {
                    mask++;
                    String cidr1 = first + "/" + mask;
                    String cidr2 = last + "/" + mask;
                    cidr1 = "CIDR=" + Subnet.normalizeCIDR(cidr1);
                    cidr2 = "CIDR=" + Subnet.normalizeCIDR(cidr2);
                    boolean splited = true;
                    try {
                        if (!CIDR.addExact(cidr1, false)) {
                            splited = false;
                        }
                    } catch (ProcessException ex) {
                        splited = false;
                    }
                    try {
                        if (!CIDR.addExact(cidr2, false)) {
                            splited = false;
                        }
                    } catch (ProcessException ex) {
                        splited = false;
                    }
                    return splited;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        }
        
        private static synchronized boolean dropExact(String token) {
            if (token == null) {
                return false;
            } else {
                int index = token.indexOf('=');
                String cidr = token.substring(index+1);
                index = token.lastIndexOf(':', index);
                String client;
                if (index == -1) {
                    client = null;
                } else {
                    client = token.substring(0, index);
                }
                TreeSet<String> set = MAP.get(client);
                if (set == null) {
                    return false;
                } else {
                    String key = Subnet.expandCIDR(cidr);
                    boolean removed = set.remove(key);
                    if (set.isEmpty()) {
                        MAP.remove(client);
                    }
                    return removed;
                }
            }
        }
        
        public static void simplify() {
            try {
                TreeSet<String> cidrSet = MAP.get(null);
                if (!cidrSet.isEmpty()) {
                    String cidrExtended = cidrSet.first();
                    do {
                        try {
                            if (cidrExtended.contains(".")) {
                                String cidrSmaller = SubnetIPv4.normalizeCIDRv4(cidrExtended);
                                int mask = Subnet.getMask(cidrSmaller);
                                if (mask > 16) {
                                    String ipFirst = SubnetIPv4.getFirstIPv4(cidrSmaller);
                                    String cidrBigger = SubnetIPv4.normalizeCIDRv4(ipFirst + "/" + (mask - 1));
                                    ipFirst = SubnetIPv4.getFirstIPv4(cidrBigger);
                                    String ipLast = SubnetIPv4.getLastIPv4(cidrBigger);
                                    String cidr1 = SubnetIPv4.normalizeCIDRv4(ipFirst + "/" + mask);
                                    if (CIDR.contains((String) null, cidr1)) {
                                        String cidr2 = SubnetIPv4.normalizeCIDRv4(ipLast + "/" + mask);
                                        if (CIDR.contains((String) null, cidr2)) {
                                            CIDR.addExact(cidrBigger, true);
                                            Server.logTrace("CIDR " + cidr1 + " and " + cidr2 + " simplified to " + cidrBigger + ".");
                                        }
                                    }
                                }
                            }
                        } catch (ProcessException ex) {
                            Server.logError(ex);
                        }
                    } while ((cidrExtended = cidrSet.higher(cidrExtended)) != null);
                }
            } catch (Exception ex) {
                Server.logError(ex);
            }
        }
        
        private static synchronized boolean addExact(
                String client, String token
        ) {
            int index = token.indexOf('=');
            String cidr = token.substring(index+1);
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                set = new TreeSet<String>();
                MAP.put(client, set);
            }
            String key = Subnet.expandCIDR(cidr);
            return set.add(key);
        }
                
        private static synchronized boolean addExact(
                String token, boolean overlap
        ) throws ProcessException {
            int index = token.indexOf('=');
            String cidr = token.substring(index+1);            
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            TreeSet<String> set = MAP.get(client);
            if (set == null) {
                set = new TreeSet<String>();
                MAP.put(client, set);
            }
            String key = Subnet.expandCIDR(cidr);
            if (set.contains(key)) {
                return false;
            } else {
                String firstCIDR = Subnet.getFirstIP(cidr);
                String lastCIDR = Subnet.getLastIP(cidr);
                String firstExpanded = Subnet.expandIP(firstCIDR) + "/00";
                String lastExpanded = Subnet.expandIP(lastCIDR) + "/99";
                String floorExpanded = set.floor(firstExpanded);
                String floor = Subnet.normalizeCIDR(floorExpanded);
                TreeSet<String> intersectsSet = new TreeSet<String>();
                intersectsSet.addAll(set.subSet(firstExpanded, lastExpanded));
                if (Subnet.containsIP(floor, firstCIDR)) {
                    intersectsSet.add(floorExpanded);
                }
                TreeSet<String> overlapSet = new TreeSet<String>();
                StringBuilder errorBuilder = new StringBuilder();
                for (String elementExpanded : intersectsSet) {
                    String element = Subnet.normalizeCIDR(elementExpanded);
                    String elementFirst = Subnet.getFirstIP(element);
                    String elementLast = Subnet.getLastIP(element);
                    if (!Subnet.containsIP(cidr, elementFirst)) {
                        errorBuilder.append("INTERSECTS ");
                        errorBuilder.append(element);
                        errorBuilder.append('\n');
                    } else if (!Subnet.containsIP(cidr, elementLast)) {
                        errorBuilder.append("INTERSECTS ");
                        errorBuilder.append(element);
                        errorBuilder.append('\n');
                    } else if (overlap) {
                        overlapSet.add(elementExpanded);
                    } else {
                        errorBuilder.append("CONTAINS ");
                        errorBuilder.append(element);
                        errorBuilder.append('\n');
                    }
                }
                String error = errorBuilder.toString();
                if (error.length() == 0) {
                    set.removeAll(overlapSet);
                    return set.add(key);
                } else {
                    throw new ProcessException(error);
                }
            }
        }
        
        public static boolean contains(Client client, String cidr) {
            if (client == null) {
                return contains((String) null, cidr);
            } else {
                return contains(client.getEmail(), cidr);
            }
        }
        
        public static boolean contains(String client, String cidr) {
            if (cidr == null) {
                return false;
            } else {
                String key = Subnet.expandCIDR(cidr);
                TreeSet<String> cidrSet = MAP.get(client);
                return cidrSet.contains(key);
            }
        }
        
        private static String getFloor(Client client, String ip) {
            TreeSet<String> cidrSet = MAP.get(client == null || !client.hasEmail() ? null : client.getEmail());
            if (cidrSet == null || cidrSet.isEmpty()) {
                return null;
            } else if (SubnetIPv4.isValidIPv4(ip)) {
                String key = SubnetIPv4.expandIPv4(ip);
                String cidr = cidrSet.floor(key + "/9");
                if (cidr == null) {
                    return null;
                } else if (cidr.contains(".")) {
                    return SubnetIPv4.normalizeCIDRv4(cidr);
                } else {
                    return null;
                }
            } else if (SubnetIPv6.isValidIPv6(ip)) {
                String key = SubnetIPv6.expandIPv6(ip);
                String cidr = cidrSet.floor(key + "/9");
                if (cidr == null) {
                    return null;
                } else if (cidr.contains(":")) {
                    return SubnetIPv6.normalizeCIDRv6(cidr);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }

        public static String get(Client client, String ip) {
            long time = System.currentTimeMillis();
            String result;
            String cidr = getFloor(null, ip);
            if (Subnet.containsIP(cidr, ip)) {
                result = "CIDR=" + cidr;
            } else if (client == null) {
                result = null;
            } else if ((cidr = getFloor(client, ip)) == null) {
                result = null;
            } else if (Subnet.containsIP(cidr, ip)) {
                result = client + ":CIDR=" + cidr;
            } else {
                result = null;
            }
            logTrace(time, "CIDR lookup for '" + ip + "'.");
            return result;
        }
    }

    public static boolean dropExact(String token) {
        if (token == null) {
            return false;
        } else if (token.contains("DNSBL=")) {
            if (DNSBL.dropExact(token)) {
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("CIDR=")) {
            if (CIDR.dropExact(token)) {
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("REGEX=")) {
            if (REGEX.dropExact(token)) {
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("WHOIS/")) {
            if (WHOIS.dropExact(token)) {
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (SET.dropExact(token)) {
            CHANGED = true;
            return true;
        } else {
            return false;
        }
    }

    public static boolean dropAll() {
        SET.clear();
        CIDR.clear();
        REGEX.clear();
        DNSBL.clear();
        WHOIS.clear();
        CHANGED = true;
        return true;
    }

    public static boolean addExact(String token) throws ProcessException {
        if (token == null) {
            return false;
        } else if (token.contains("WHOIS/")) {
            if (WHOIS.addExact(token)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("DNSBL=")) {
            if (DNSBL.addExact(token)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("CIDR=")) {
            if (CIDR.addExact(token, false)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (token.contains("REGEX=")) {
            if (REGEX.addExact(token)) {
                Peer.releaseAll(token);
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        } else if (SET.addExact(token)) {
            Peer.releaseAll(token);
            CHANGED = true;
            return true;
        } else {
            return false;
        }
    }
    
    public static TreeSet<String> getExtendedCIDR() {
        return CIDR.getExtended();
    }

    public static TreeSet<String> getAll() throws ProcessException {
        TreeSet<String> blockSet = SET.getAll();
        blockSet.addAll(CIDR.getAll());
        blockSet.addAll(REGEX.getAll());
        blockSet.addAll(DNSBL.getAll());
        blockSet.addAll(WHOIS.getAll());
        return blockSet;
    }

    public static boolean containsExact(String token) {
        if (token.contains("WHOIS/")) {
            int index = token.indexOf('/');
            String whois = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            return WHOIS.contains(client, whois);
        } else if (token.contains("DNSBL=")) {
            int index = token.indexOf('=');
            String dnsbl = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            return DNSBL.contains(client, dnsbl);
        } else if (token.contains("CIDR=")) {
            int index = token.indexOf('=');
            String cidr = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            return CIDR.contains(client, cidr);
        } else if (token.contains("REGEX=")) {
            int index = token.indexOf('=');
            String regex = token.substring(index+1);
            index = token.lastIndexOf(':', index);
            String client;
            if (index == -1) {
                client = null;
            } else {
                client = token.substring(0, index);
            }
            return REGEX.contains(client, regex);
        } else {
            return SET.contains(token);
        }
    }

    private static String addDomain(String user, String token) {
        try {
            if (token == null) {
                return null;
            } else if (token.startsWith("@") && (token = Domain.extractDomain(token.substring(1), true)) != null) {
                if (user == null && addExact(token)) {
                    return token;
                } else if (user != null && addExact(user + ':' + token)) {
                    return user + ':' + token;
                } else {
                    return null;
                }
            } else if (token.startsWith(".") && (token = Domain.extractDomain(token, true)) != null) {
                if (user == null && addExact(token)) {
                    return token;
                } else if (user != null && addExact(user + ':' + token)) {
                    return user + ':' + token;
                } else {
                    return null;
                }
            } else {
                return null;
            }
        } catch (ProcessException ex) {
            return null;
        }
    }
    
    private static String normalizeTokenBlock(String token) throws ProcessException {
        return SPF.normalizeToken(token, true, true, true, true, false);
    }
    
    public static boolean tryAdd(String token) {
        try {
            return add(token) != null;
        } catch (ProcessException ex) {
            return false;
        }
    }

    public static String add(String token) throws ProcessException {
        if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("ERROR: TOKEN INVALID");
        } else if (addExact(token)) {
            return token;
        } else {
            return null;
        }
    }
    
    public static boolean overlap(String cidr) throws ProcessException {
        if ((cidr = normalizeTokenBlock(cidr)) == null) {
            throw new ProcessException("ERROR: TOKEN INVALID");
        } else if (!cidr.startsWith("CIDR=")) {
            throw new ProcessException("ERROR: TOKEN INVALID");
        } else {
            return CIDR.addExact(cidr, true);
        }
    }
    
    public static boolean add(String client, String token) throws ProcessException {
        if (client == null || !Domain.isEmail(client)) {
            throw new ProcessException("ERROR: CLIENT INVALID");
        } else if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("ERROR: TOKEN INVALID");
        } else {
            return addExact(client + ':' + token);
        }
    }

    public static boolean add(Client client, String token) throws ProcessException {
        if (client == null || !client.hasEmail()) {
            throw new ProcessException("ERROR: CLIENT INVALID");
        } else if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("ERROR: TOKEN INVALID");
        } else {
            return addExact(client.getEmail() + ':' + token);
        }
    }

    public static boolean drop(String token) throws ProcessException {
        if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("ERROR: TOKEN INVALID");
        } else if (dropExact(token)) {
            return true;
        } else {
            return false;
        }
    }
    
    public static boolean drop(String client, String token) throws ProcessException {
        if (client == null || !Domain.isEmail(client)) {
            throw new ProcessException("ERROR: CLIENT INVALID");
        } else if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("ERROR: TOKEN INVALID");
        } else {
            return dropExact(client + ':' + token);
        }
    }

    public static boolean drop(Client client, String token) throws ProcessException {
        if (client == null || !client.hasEmail()) {
            throw new ProcessException("ERROR: CLIENT INVALID");
        } else if ((token = normalizeTokenBlock(token)) == null) {
            throw new ProcessException("ERROR: TOKEN INVALID");
        } else {
            return dropExact(client.getEmail() + ':' + token);
        }
    }

    public static TreeSet<String> get(Client client) throws ProcessException {
        TreeSet<String> blockSet = new TreeSet<String>();
        if (client != null && client.hasEmail()) {
            for (String token : getAll()) {
                if (token.startsWith(client.getEmail() + ':')) {
                    int index = token.indexOf(':') + 1;
                    token = token.substring(index);
                    blockSet.add(token);
                }
            }
        }
        return blockSet;
    }

    public static TreeSet<String> getAll(Client client) throws ProcessException {
        TreeSet<String> blockSet = new TreeSet<String>();
        for (String token : getAll()) {
            if (!token.contains(":")) {
                blockSet.add(token);
            } else if (client != null && client.hasEmail() && token.startsWith(client.getEmail() + ':')) {
                int index = token.indexOf(':') + 1;
                token = token.substring(index);
                blockSet.add(token);
            }
        }
        return blockSet;
    }

    public static TreeSet<String> getAllTokens(String value) {
        TreeSet<String> blockSet = new TreeSet<String>();
        if (Subnet.isValidIP(value)) {
            String ip = Subnet.normalizeIP(value);
            if (SET.contains(ip)) {
                blockSet.add(ip);
            }
        } else if (Subnet.isValidCIDR(value)) {
            String cidr = Subnet.normalizeCIDR(value);
            if (CIDR.contains((String) null, cidr)) {
                blockSet.add(cidr);
            }
            TreeSet<String> set = SET.getAll();
            for (String ip : set) {
                if (Subnet.containsIP(cidr, ip)) {
                    blockSet.add(ip);
                }
            }
            for (String ip : set) {
                if (SubnetIPv6.containsIP(cidr, ip)) {
                    blockSet.add(ip);
                }
            }
        } else if (Domain.isHostname(value)) {
            LinkedList<String> regexList = new LinkedList<String>();
            String host = Domain.normalizeHostname(value, true);
            do {
                int index = host.indexOf('.') + 1;
                host = host.substring(index);
                if (Block.dropExact('.' + host)) {
                    blockSet.add('.' + host);
                    regexList.addFirst('.' + host);
                }
            } while (host.contains("."));
        } else if (SET.contains(value)) {
            blockSet.add(value);
        }
        return blockSet;
    }

    public static TreeSet<String> get() throws ProcessException {
        TreeSet<String> blockSet = new TreeSet<String>();
        for (String token : getAll()) {
            if (!token.contains(":")) {
                blockSet.add(token);
            }
        }
        return blockSet;
    }
    
    public static void clear(String token, String name) {
        try {
            String block;
            while ((block = Block.find(null, token, false)) != null) {
                if (Block.drop(block)) {
                    Server.logDebug("false positive BLOCK '" + block + "' detected by '" + name + "'.");
                }
            }
        } catch (ProcessException ex) {
            Server.logError(ex);
        }
    }
    
    public static String clearCIDR(String ip, int mask) {
        if (SubnetIPv4.isValidIPv4(ip)) {
            String cidr;
            while ((cidr = CIDR.get(null, ip)) != null && Subnet.getMask(cidr) < mask) {
                CIDR.split(cidr);
            }
            if (CIDR.dropExact(cidr)) {
                return cidr;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }
    
    public static boolean clearCIDR(String ip, String admin) {
        if (ip == null) {
            return false;
        } else {
            String cidr;
            int mask = SubnetIPv4.isValidIPv4(ip) ? 32 : 64;
            if ((cidr = Block.clearCIDR(ip, mask)) != null) {
                Server.logDebug("false positive BLOCK '" + cidr + "' detected by '" + admin + "'.");
                return true;
            } else {
                return false;
            }
        }
    }
    
    public static String find(
            Client client,
            String token,
            boolean autoBlock
            ) throws ProcessException {
        TreeSet<String> whoisSet = new TreeSet<String>();
        LinkedList<String> regexList = new LinkedList<String>();
        if (token == null) {
            return null;
        } else if (Domain.isEmail(token)) {
            String sender = token.toLowerCase();
            int index1 = sender.indexOf('@');
            int index2 = sender.lastIndexOf('@');
            String part = sender.substring(0, index1 + 1);
            String senderDomain = sender.substring(index2);
            if (SET.contains(sender)) {
                return sender;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + sender)) {
                return sender;
            } else if (SET.contains(part)) {
                return part;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + part)) {
                return part;
            } else if (SET.contains(senderDomain)) {
                return senderDomain;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + senderDomain)) {
                return senderDomain;
            } else {
                int index3 = senderDomain.length();
                while ((index3 = senderDomain.lastIndexOf('.', index3 - 1)) > index2) {
                    String subdomain = senderDomain.substring(0, index3 + 1);
                    if (SET.contains(subdomain)) {
                        return subdomain;
                    } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subdomain)) {
                        return subdomain;
                    }
                }
                String host = '.' + senderDomain.substring(1);
                do {
                    int index = host.indexOf('.') + 1;
                    host = host.substring(index);
                    String token2 = '.' + host;
                    if (SET.contains(token2)) {
                        return token2;
                    } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + token2)) {
                        return token2;
                    }
                    regexList.addFirst(token2);
                } while (host.contains("."));
                int index4 = sender.length();
                while ((index4 = sender.lastIndexOf('.', index4 - 1)) > index2) {
                    String subsender = sender.substring(0, index4 + 1);
                    if (SET.contains(subsender)) {
                        return subsender;
                    } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subsender)) {
                        return subsender;
                    }
                }
            }
            if (senderDomain.endsWith(".br")) {
                whoisSet.add(senderDomain);
            }
            regexList.add(sender);
        } else if (Subnet.isValidIP(token)) {
            token = Subnet.normalizeIP(token);
            String cidr;
            String dnsbl;
            if (SET.contains(token)) {
                return token;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + token)) {
                return token;
            } else if ((cidr = CIDR.get(client, token)) != null) {
                return cidr;
            } else if ((dnsbl = DNSBL.get(client, token)) != null) {
                return dnsbl;
            }
            Reverse reverse = Reverse.get(token);
            if (reverse != null) {
                for (String host : reverse.getAddressSet()) {
                    String block = find(client, host, autoBlock);
                    if (block != null) {
                        return block;
                    }
                }
            }
            regexList.add(token);
        } else if (Domain.isHostname(token)) {
            token = Domain.normalizeHostname(token, true);
            String host = token;
            do {
                int index = host.indexOf('.') + 1;
                host = host.substring(index);
                String token2 = '.' + host;
                if (SET.contains(token2)) {
                    return token2;
                } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + token2)) {
                    return token2;
                }
                regexList.addFirst(token2);
            } while (host.contains("."));
            if (token.endsWith(".br")) {
                whoisSet.add(token);
            }
        } else {
            regexList.add(token);
        }
        try {
            // Verifica um critério do REGEX.
            String regex;
            if ((regex = REGEX.get(client, regexList, autoBlock)) != null) {
                return regex;
            }
        } catch (Exception ex) {
            Server.logError(ex);
        }
        try {
            // Verifica critérios do WHOIS.
            String whois;
            if ((whois = WHOIS.get(client, whoisSet, autoBlock)) != null) {
                return whois;
            }
        } catch (Exception ex) {
            Server.logError(ex);
        }
        return null;
    }

    public static boolean contains(Client client,
            String ip, String sender, String helo,
            String qualifier, String recipient
            ) throws ProcessException {
        return find(client, ip, sender, helo,
                qualifier, recipient) != null;
    }

    public static void clear(
            Client client,
            User user,
            String ip,
            String sender,
            String helo,
            String qualifier,
            String recipient
            ) throws ProcessException {
        if (qualifier.equals("PASS")) {
            String block;
            int mask = SubnetIPv4.isValidIPv4(ip) ? 32 : 64;
            if ((block = Block.clearCIDR(ip, mask)) != null) {
                if (user == null) {
                    Server.logDebug("false positive BLOCK '" + block + "' detected.");
                } else {
                    Server.logDebug("false positive BLOCK '" + block + "' detected by '" + user.getEmail() + "'.");
                }
            }
            while (dropExact(block = find(client, ip, sender, helo, qualifier, recipient))) {
                if (user == null) {
                    Server.logDebug("false positive BLOCK '" + block + "' detected.");
                } else {
                    Server.logDebug("false positive BLOCK '" + block + "' detected by '" + user.getEmail() + "'.");
                }
            }
        }
    }

    public static String find(
            Client client,
            String ip,
            String sender,
            String helo,
            String qualifier,
            String recipient
            ) throws ProcessException {
        TreeSet<String> whoisSet = new TreeSet<String>();
        TreeSet<String> regexSet = new TreeSet<String>();
        // Definição do destinatário.
        String recipientDomain;
        if (recipient != null && recipient.contains("@")) {
            int index = recipient.indexOf('@');
            recipient = recipient.toLowerCase();
            recipientDomain = recipient.substring(index);
        } else {
            recipient = null;
            recipientDomain = null;
        }
        // Verifica o remetente.
        if (sender != null && sender.contains("@")) {
            sender = sender.toLowerCase();
            int index1 = sender.indexOf('@');
            int index2 = sender.lastIndexOf('@');
            String part = sender.substring(0, index1 + 1);
            String senderDomain = sender.substring(index2);
            if (SET.contains(sender)) {
                return sender;
            } else if (SET.contains(sender + ';' + qualifier + '>' + recipient)) {
                return sender + ';' + qualifier + '>' + recipient;
            } else if (SET.contains(sender + ';' + qualifier + '>' + recipientDomain)) {
                return sender + ';' + qualifier + '>' + recipientDomain;
            } else if (SET.contains(sender + ';' + qualifier)) {
                return sender + ';' + qualifier;
            } else if (SET.contains(sender + ';' + qualifier + '>' + recipient)) {
                return sender + ';' + qualifier + '>' + recipient;
            } else if (SET.contains(sender + ';' + qualifier + '>' + recipientDomain)) {
                return sender + ';' + qualifier + '>' + recipientDomain;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + sender)) {
                return sender;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + sender + '>' + recipient)) {
                return sender + '>' + recipient;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + sender + '>' + recipientDomain)) {
                return sender + '>' + recipientDomain;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + sender + ';' + qualifier)) {
                return sender + ';' + qualifier;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + sender + ';' + qualifier + '>' + recipient)) {
                return sender + ';' + qualifier + '>' + recipient;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + sender + ';' + qualifier + '>' + recipientDomain)) {
                return sender + ';' + qualifier + '>' + recipientDomain;
            } else if (SET.contains(part)) {
                return part;
            } else if (SET.contains(part + '>' + recipient)) {
                return part + '>' + recipient;
            } else if (SET.contains(part + '>' + recipientDomain)) {
                return part + '>' + recipientDomain;
            } else if (SET.contains(part + ';' + qualifier)) {
                return part + ';' + qualifier;
            } else if (SET.contains(part + ';' + qualifier + '>' + recipient)) {
                return part + ';' + qualifier + '>' + recipient;
            } else if (SET.contains(part + ';' + qualifier + '>' + recipientDomain)) {
                return part + ';' + qualifier + '>' + recipientDomain;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + part)) {
                return part;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + part + '>' + recipient)) {
                return part + '>' + recipient;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + part + '>' + recipientDomain)) {
                return part + '>' + recipientDomain;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + part + ';' + qualifier)) {
                return part + ';' + qualifier;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + part + ';' + qualifier + '>' + recipient)) {
                return part + ';' + qualifier + '>' + recipient;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + part + ';' + qualifier + '>' + recipientDomain)) {
                return part + ';' + qualifier + '>' + recipientDomain;
            } else if (SET.contains(senderDomain)) {
                return senderDomain;
            } else if (SET.contains(senderDomain + '>' + recipient)) {
                return senderDomain + '>' + recipient;
            } else if (SET.contains(senderDomain + '>' + recipientDomain)) {
                return senderDomain + '>' + recipientDomain;
            } else if (SET.contains(senderDomain + ';' + qualifier)) {
                return senderDomain + ';' + qualifier;
            } else if (SET.contains(senderDomain + ';' + qualifier + '>' + recipient)) {
                return senderDomain + ';' + qualifier + '>' + recipient;
            } else if (SET.contains(senderDomain + ';' + qualifier + '>' + recipientDomain)) {
                return senderDomain + ';' + qualifier + '>' + recipientDomain;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + senderDomain)) {
                return senderDomain;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + senderDomain + '>' + recipient)) {
                return senderDomain + '>' + recipient;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + senderDomain + '>' + recipientDomain)) {
                return senderDomain + '>' + recipientDomain;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + senderDomain + ';' + qualifier)) {
                return senderDomain + ';' + qualifier;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + senderDomain + ';' + qualifier + '>' + recipient)) {
                return  senderDomain + ';' + qualifier + '>' + recipient;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + senderDomain + ';' + qualifier + '>' + recipientDomain)) {
                return senderDomain + ';' + qualifier + '>' + recipientDomain;
            } else {
                String host = findHost(client, senderDomain.substring(1), qualifier, recipient, recipientDomain);
                if (host != null) {
                    return host;
                } else {
                    int index3 = senderDomain.length();
                    while ((index3 = senderDomain.lastIndexOf('.', index3 - 1)) > index2) {
                        String subdomain = senderDomain.substring(0, index3 + 1);
                        if (SET.contains(subdomain)) {
                            return subdomain;
                        } else if (SET.contains(subdomain + '>' + recipient)) {
                            return subdomain + '>' + recipient;
                        } else if (SET.contains(subdomain + '>' + recipientDomain)) {
                            return subdomain + '>' + recipientDomain;
                        } else if (SET.contains(subdomain + ';' + qualifier)) {
                            return subdomain + ';' + qualifier;
                        } else if (SET.contains(subdomain + ';' + qualifier + '>' + recipient)) {
                            return subdomain + ';' + qualifier + '>' + recipient;
                        } else if (SET.contains(subdomain + ';' + qualifier + '>' + recipientDomain)) {
                            return subdomain + ';' + qualifier + '>' + recipientDomain;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subdomain)) {
                            return subdomain;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subdomain + '>' + recipient)) {
                            return subdomain + '>' + recipient;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subdomain + '>' + recipientDomain)) {
                            return subdomain + '>' + recipientDomain;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subdomain + ';' + qualifier)) {
                            return subdomain + ';' + qualifier;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subdomain + ';' + qualifier + '>' + recipient)) {
                            return subdomain + ';' + qualifier + '>' + recipient;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subdomain + ';' + qualifier + '>' + recipientDomain)) {
                            return subdomain + ';' + qualifier + '>' + recipientDomain;
                        }
                    }
                    int index4 = sender.length();
                    while ((index4 = sender.lastIndexOf('.', index4 - 1)) > index2) {
                        String subsender = sender.substring(0, index4 + 1);
                        if (SET.contains(subsender)) {
                            return subsender;
                        } else if (SET.contains(subsender + '>' + recipient)) {
                            return subsender + '>' + recipient;
                        } else if (SET.contains(subsender + '>' + recipientDomain)) {
                            return subsender + '>' + recipientDomain;
                        } else if (SET.contains(subsender + ';' + qualifier)) {
                            return subsender + ';' + qualifier;
                        } else if (SET.contains(subsender + ';' + qualifier + '>' + recipient)) {
                            return subsender + ';' + qualifier + '>' + recipient;
                        } else if (SET.contains(subsender + ';' + qualifier + '>' + recipientDomain)) {
                            return subsender + ';' + qualifier + '>' + recipientDomain;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subsender)) {
                            return subsender;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subsender + '>' + recipient)) {
                            return subsender + '>' + recipient;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subsender + '>' + recipientDomain)) {
                            return subsender + '>' + recipientDomain;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subsender + ';' + qualifier)) {
                            return subsender + ';' + qualifier;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subsender + ';' + qualifier + '>' + recipient)) {
                            return subsender + ';' + qualifier + '>' + recipient;
                        } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + subsender + ';' + qualifier + '>' + recipientDomain)) {
                            return subsender + ';' + qualifier + '>' + recipientDomain;
                        }
                    }
                }
            }
            if (senderDomain.endsWith(".br")) {
                whoisSet.add(senderDomain);
            }
            regexSet.add(sender);
            regexSet.add(senderDomain);
        }
        // Verifica o HELO.
        if ((helo = Domain.extractHost(helo, true)) != null) {
            String host = findHost(client, helo, qualifier, recipient, recipientDomain);
            if (host != null) {
                return host;
            }
            if (helo.endsWith(".br") && SPF.matchHELO(ip, helo)) {
                whoisSet.add(helo);
            }
            regexSet.add(helo);
        }
        // Verifica o IP.
        if (ip != null) {
            ip = Subnet.normalizeIP(ip);
            String cidr;
            String dnsbl;
            if (SET.contains(ip)) {
                return ip;
            } else if (SET.contains(ip + '>' + recipient)) {
                return ip + '>' + recipient;
            } else if (SET.contains(ip + '>' + recipientDomain)) {
                return ip + '>' + recipientDomain;
            } else if (SET.contains(ip + ';' + qualifier)) {
                return ip + ';' + qualifier;
            } else if (SET.contains(ip + ';' + qualifier + '>' + recipient)) {
                return ip + ';' + qualifier + '>' + recipient;
            } else if (SET.contains(ip + ';' + qualifier + '>' + recipientDomain)) {
                return ip + ';' + qualifier + '>' + recipientDomain;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + ip)) {
                return ip;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + ip + '>' + recipient)) {
                return ip + '>' + recipient;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + ip + '>' + recipientDomain)) {
                return ip + '>' + recipientDomain;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + ip + ';' + qualifier)) {
                return ip + ';' + qualifier;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + ip + ';' + qualifier + '>' + recipient)) {
                return ip + ';' + qualifier + '>' + recipient;
            } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + ip + ';' + qualifier + '>' + recipientDomain)) {
                return ip + ';' + qualifier + '>' + recipientDomain;
            } else if ((cidr = CIDR.get(client, ip)) != null) {
                return cidr;
            } else if ((dnsbl = DNSBL.get(client, ip)) != null) {
                return dnsbl;
            }
            Reverse reverse = Reverse.get(ip);
            if (reverse != null) {
                for (String host : reverse.getAddressSet()) {
                    String block = find(client, host, true);
                    if (block != null) {
                        return block;
                    }
                }
            }
            regexSet.add(ip);
        }
        try {
            // Verifica um critério do REGEX.
            String regex;
            if ((regex = REGEX.get(client, regexSet, true)) != null) {
                return regex;
            }
        } catch (Exception ex) {
            Server.logError(ex);
        }
        try {
            // Verifica critérios do WHOIS.
            String whois;
            if ((whois = WHOIS.get(client, whoisSet, true)) != null) {
                return whois;
            }
        } catch (Exception ex) {
            Server.logError(ex);
        }
        return null;
    }
    
    public static boolean containsCIDR(String ip) {
        if ((ip = Subnet.normalizeIP(ip)) == null) {
            return false;
        } else {
            return CIDR.get(null, ip) != null;
        }
    }
    
    public static boolean containsDNSBL(String ip) {
        if ((ip = Subnet.normalizeIP(ip)) == null) {
            return false;
        } else {
            return DNSBL.get(null, ip) != null;
        }
    }

    private static int parseIntWHOIS(String value) {
        try {
            if (value == null || value.length() == 0) {
                return 0;
            } else {
                Date date = Domain.DATE_FORMATTER.parse(value);
                long time = date.getTime() / (1000 * 60 * 60 * 24);
                long today = System.currentTimeMillis() / (1000 * 60 * 60 * 24);
                return (int) (today - time);
            }
        } catch (Exception ex) {
            try {
                return Integer.parseInt(value);
            } catch (Exception ex2) {
                return 0;
            }
        }
    }
    
    public static boolean containsDomain(String host) {
        return containsDomain(null, host);
    }
    
    public static boolean containsDomainIP(String host, String ip) {
        if (containsCIDR(ip)) {
            return true;
        } else if (containsDomain(null, host)) {
            return true;
        } else {
            return false;
        }
    }
    
    public static boolean containsDomain(String client, String host) {
        host = Domain.extractHost(host, true);
        if (host == null) {
            return false;
        } else {
            do {
                int index = host.indexOf('.') + 1;
                host = host.substring(index);
                String token = '.' + host;
                if (SET.contains(token)) {
                    return true;
                } else if (client != null && SET.contains(client + ':' + token)) {
                    return true;
                }
            } while (host.contains("."));
            return false;
        }
    }
    
    public static boolean containsREGEX(
            String host
            ) throws ProcessException {
        host = Domain.extractHost(host, true);
        if (host == null) {
            return false;
        } else {
            LinkedList<String> tokenList = new LinkedList<String>();
            do {
                int index = host.indexOf('.') + 1;
                host = host.substring(index);
                String token = '.' + host;
                tokenList.addFirst(token);
            } while (host.contains("."));
            return REGEX.get(null, tokenList, true) != null;
        }
    }
    
    public static boolean containsWHOIS(
            String host
            ) throws ProcessException {
        host = Domain.extractHost(host, true);
        if (host == null) {
            return false;
        } else if (host.endsWith(".br")) {
            try {
                TreeSet<String> tokenSet = new TreeSet<String>();
                tokenSet.add(host);
                return WHOIS.get(null, tokenSet, true) != null;
            } catch (Exception ex) {
                Server.logError(ex);
                return false;
            }
        } else {
            return false;
        }
    }

    private static String findHost(Client client,
            String host, String qualifier,
            String recipient, String recipientDomain) {
        host = Domain.extractHost(host, true);
        if (host == null) {
            return null;
        } else {
            do {
                int index = host.indexOf('.') + 1;
                host = host.substring(index);
                String token = '.' + host;
                if (SET.contains(token)) {
                    return token;
                } else if (SET.contains(token + '>' + recipient)) {
                    return token + '>' + recipient;
                } else if (SET.contains(token + '>' + recipientDomain)) {
                    return token + '>' + recipientDomain;
                } else if (SET.contains(token + ';' + qualifier)) {
                    return token + ';' + qualifier;
                } else if (SET.contains(token + ';' + qualifier + '>' + recipient)) {
                    return token + ';' + qualifier + '>' + recipient;
                } else if (SET.contains(token + ';' + qualifier + '>' + recipientDomain)) {
                    return token + ';' + qualifier + '>' + recipientDomain;
                } else if (client != null && client.hasEmail() && SET.contains(client.getEmail() + ':' + token)) {
                    return token;
                } else if (client != null && client.hasEmail()  && SET.contains(client.getEmail() + ':' + token + '>' + recipient)) {
                    return token + '>' + recipient;
                } else if (client != null && client.hasEmail()  && SET.contains(client.getEmail() + ':' + token + '>' + recipientDomain)) {
                    return token + '>' + recipientDomain;
                } else if (client != null && client.hasEmail()  && SET.contains(client.getEmail() + ':' + token + ';' + qualifier)) {
                    return token + ';' + qualifier;
                } else if (client != null && client.hasEmail()  && SET.contains(client.getEmail() + ':' + token + ';' + qualifier + '>' + recipient)) {
                    return token + ';' + qualifier + '>' + recipient;
                } else if (client != null && client.hasEmail()  && SET.contains(client.getEmail() + ':' + token + ';' + qualifier + '>' + recipientDomain)) {
                    return token + ';' + qualifier + '>' + recipientDomain;
                }
            } while (host.contains("."));
            return null;
        }
    }

    public static void store() {
        if (CHANGED) {
            try {
                CIDR.simplify();
                long time = System.currentTimeMillis();
                File file = new File("./data/block.set");
                TreeSet<String> set = getAll();
                FileOutputStream outputStream = new FileOutputStream(file);
                try {
                    SerializationUtils.serialize(set, outputStream);
                    CHANGED = false;
                } finally {
                    outputStream.close();
                }
                Server.logStore(time, file);
            } catch (Exception ex) {
                Server.logError(ex);
            }
        }
    }

    public static void load() {
        long time = System.currentTimeMillis();
        File file = new File("./data/block.set");
        if (file.exists()) {
            try {
                Set<String> set;
                FileInputStream fileInputStream = new FileInputStream(file);
                try {
                    set = SerializationUtils.deserialize(fileInputStream);
                } finally {
                    fileInputStream.close();
                }
                for (String token : set) {
                    String client;
                    String identifier;
                    if (token.contains(":")) {
                        int index = token.indexOf(':');
                        client = token.substring(0, index);
                        identifier = token.substring(index + 1);
                    } else {
                        client = null;
                        identifier = token;
                    }
                    if (Domain.isHostname(token)) {
                        boolean add = true;
                        String hostname = token;
                        int index;
                        while ((index = hostname.indexOf('.', 1)) != -1) {                                
                            hostname = hostname.substring(index);
                            if (set.contains(hostname)) {
                                add = false;
                                break;
                            }
                        }
                        if (add) {
                            SET.addExact(token);
                        }
                    } else if (token.startsWith("@") && Domain.isHostname(token.substring(1))) {
                        boolean add = true;
                        String hostname = '.' + token.substring(1);
                        if (set.contains(hostname)) {
                            add = false;
                        } else {
                            int index;
                            while ((index = hostname.indexOf('.', 1)) != -1) {                                
                                hostname = hostname.substring(index);
                                if (set.contains(hostname)) {
                                    add = false;
                                    break;
                                }
                            }
                        }
                        if (add) {
                            SET.addExact(token);
                        }
                    } else if (identifier.startsWith("CIDR=")) {
                        CIDR.addExact(client, identifier);
                    } else {
                        addExact(token);
                    }
                }
                CHANGED = false;
                Server.logLoad(time, file);
            } catch (Exception ex) {
                Server.logError(ex);
            }
        }
    }
}

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
package net.spfbl.core;

import net.spfbl.data.Block;
import net.spfbl.data.Ignore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.spfbl.spf.SPF;
import net.spfbl.spf.SPF.Distribution;
import net.spfbl.spf.SPF.Status;
import net.spfbl.whois.Domain;
import net.spfbl.whois.Subnet;
import net.spfbl.whois.SubnetIPv6;
import org.apache.commons.lang3.SerializationUtils;

/**
 * Representa um peer do sistema P2P.
 * 
 * @author Leandro Carlos Rodrigues <leandro@spfbl.net>
 */
public final class Peer implements Serializable, Comparable<Peer> {
    
    private static final long serialVersionUID = 1L;
    
    private final String address; // Endereço de acesso ao peer.
    private short port; // Porta de acesso ao peer.
    private Send send = Send.NEVER; // Status de envio para este peer.
    private Receive receive = Receive.REJECT; // Status de recebimento deste peer.
    private String email = null; // E-mail do responsável.
    private int limit = 100;
    private NormalDistribution frequency = null;
    private long last = 0; // Último recebimento.
    
    /**
     * Tabela de reputação do peer.
     */
    private final HashMap<String,Binomial> reputationMap = null;
    private TreeMap<String,Binomial> reputationMap2 = new TreeMap<String,Binomial>();
    
    private Peer(Peer other) {
        this.address = other.address;
        this.port = other.port;
        this.send = other.send;
        this.receive = other.receive;
        this.email = other.email;
        this.limit = other.limit;
        if (other.frequency == null) {
            this.frequency = null;
        } else {
            this.frequency = other.frequency.replicate();
        }
        this.last = other.last;
        this.reputationMap2.putAll(other.reputationMap2);
    }
    
    /**
     * Retém os bloqueios que necessitam de confirmação.
     */
    private TreeSet<String> retainSet = new TreeSet<String>();
    
    public enum Send {
        NEVER, // Nunca enviar bloqueios e tabela de reputação para este peer.
        DUNNO, // Obsoleto.
        REPUTATION, // Sempre enviar somente a tabela de reputação.
        ALWAYS, // Sempre enviar os bloqueios e tabela de reputação.
        BLOCK, // Obsoleto.
        REPASS, // Envia e repassa todos os bloqueios recebidos.
        MIRROR // Trata o peer como espelho no envio.
    }
    
    public enum Receive {
        ACCEPT, // Aceita imediatamente os bloqueios sem repassar.
        REJECT, // Ignora todos os bloqueios recebidos.
        DROP, // Decarta todos os bloqueios recebidos e manda o firewall dropar.
        CONFIRM, // Obsoleto.
        RETAIN, // Retém todos os bloqueios recebidos para confirmação.
        REPASS, // Aceita e repassa imediatamente os bloqueios.
        MIRROR // Trata o peer como espelho no recebimento.
     }
    
    private Peer(String address, int port) throws ProcessException {
        if (Domain.isHostname(address) || Subnet.isValidIP(address)) {
            this.address = Domain.extractHost(address, false);
            this.setPort(port);
        } else {
            throw new ProcessException("ERROR: INVALID PEER");
        }
    }
    
    public boolean setPort(int port) throws ProcessException {
        if (port < 1024 || port >= 49152) {
            throw new ProcessException("ERROR: INVALID PORT");
        } else if (this.port != port) {
            this.port = (short) port;
            CHANGED = true;
            return true;
        } else {
            return false;
        }
    }
    
    public boolean setPort(String port) throws ProcessException {
        try {
            int portInt = Integer.parseInt(port);
            return setPort(portInt);
        } catch (NumberFormatException ex) {
            throw new ProcessException("ERROR: INVALID PORT", ex);
        }
    }
    
    public String getAddress() {
        return address;
    }
    
    public InetAddress getInetAddress() throws UnknownHostException {
        return InetAddress.getByName(address);
    }
    
    public String getIP() throws UnknownHostException {
        InetAddress inetAddress = getInetAddress();
        String ip = inetAddress.getHostAddress();
        return Subnet.normalizeIP(ip);
    }
    
    public short getPort() {
        return port;
    }
    
    public Send getSendStatus() {
        return send;
    }
    
    public Receive getReceiveStatus() {
        return receive;
    }
    
    public String getEmail() {
        return email;
    }
    
    public User getUser() {
        return User.get(email);
    }
    
    public boolean isAlive() {
        return (System.currentTimeMillis() - last) / Server.DAY_TIME == 0;
    }
    
    public void setEmail(String email) throws ProcessException {
        if (email == null || email.length() == 0) {
            if (this.email != null) {
                this.email = null;
                CHANGED = true;
            }
        } else if (Domain.isEmail(email)) {
            if (!email.toLowerCase().equals(this.email)) {
                this.email = email.toLowerCase();
                CHANGED = true;
            }
        } else {
            throw new ProcessException("ERROR: INVALID EMAIL");
        }
    }
    
    public boolean setSendStatus(Send status) throws ProcessException {
        if (status == null) {
            throw new ProcessException("INVALID SEND");
        } else if (status == Send.BLOCK) {
            throw new ProcessException("INVALID SEND");
        } else if (status == Send.DUNNO) {
            throw new ProcessException("INVALID SEND");
        } else if (status == this.send) {
            return false;
        } else {
            this.send = status;
            CHANGED = true;
            return true;
        }
    }
    
    public boolean setSendStatus(String status) throws ProcessException {
        try {
            return setSendStatus(Send.valueOf(status));
        } catch (IllegalArgumentException ex) {
            throw new ProcessException("INVALID SEND");
        }
    }
    
    public boolean setReceiveStatus(Receive status) throws ProcessException {
        if (status == null) {
            throw new ProcessException("INVALID RECEIVE");
        } else if (status == Receive.CONFIRM) {
            throw new ProcessException("INVALID RECEIVE");
        } else if (status == this.receive) {
            return false;
        } else {
            this.receive = status;
            CHANGED = true;
            return true;
        }
    }
    
    public boolean setReceiveStatus(String status) throws ProcessException {
        try {
            return setReceiveStatus(Receive.valueOf(status));
        } catch (IllegalArgumentException ex) {
            throw new ProcessException("INVALID RECEIVE");
        }
    }
    
    public synchronized Binomial getBinomial(String key) {
        return reputationMap2.get(key);
    }
    
    public synchronized TreeSet<String> getReputationKeySet() {
        TreeSet<String> keySet = new TreeSet<String>();
        keySet.addAll(reputationMap2.keySet());
        return keySet;
    }
    
    public synchronized TreeMap<String,Binomial> getReputationMap() {
        TreeMap<String,Binomial> returnSet = new TreeMap<String,Binomial>();
        returnSet.putAll(reputationMap2);
        return returnSet;
    }
    
    public TreeSet<String> getRetationSet() {
        TreeSet<String> returnSet = new TreeSet<String>();
        returnSet.addAll(retainSet);
        return returnSet;
    }
    
    public static TreeSet<String> getAllRetationSet() {
        TreeSet<String> returnSet = new TreeSet<String>();
        for (Peer peer : Peer.getSet()) {
            for (String token : peer.getRetationSet()) {
                returnSet.add(peer.getAddress() + ':' + token);
            }
        }
        return returnSet;
    }
    
    public synchronized boolean dropExact(String token) {
        return retainSet.remove(token);
    }
    
    public String reject(String token) {
        if (dropExact(token)) {
            return "REJECTED";
        } else {
            return "NOT FOUND";
        }
    }
    
    public TreeSet<String> reject() {
        TreeSet<String> returnSet = new TreeSet<String>();
        for (String token : getRetationSet()) {
            String response = reject(token);
            if (!response.equals("NOT FOUND")) {
                returnSet.add(token + " => " + response);
            }
        }
        return returnSet;
    }
    
    public static TreeSet<String> getReputationKeyAllSet() {
        TreeSet<String> keySet = new TreeSet<String>();
        for (Peer peer : Peer.getSet()) {
            keySet.addAll(peer.getReputationKeySet());
        }
        return keySet;
    }
    
    public static TreeSet<String> rejectAll() {
        TreeSet<String> returnSet = new TreeSet<String>();
        for (Peer peer : Peer.getSet()) {
            for (String response : peer.reject()) {
                returnSet.add(peer.getAddress() + ':' + response);
            }
        }
        return returnSet;
    }
    
    public static TreeSet<String> rejectAll(String token) {
        TreeSet<String> returnSet = new TreeSet<String>();
        for (Peer peer : Peer.getSet()) {
            String response = peer.reject(token);
            returnSet.add(peer.getAddress() + ':' + response);
        }
        return returnSet;
    }
    
    public String release(String token) {
        if (dropExact(token)) {
            try {
                if (Ignore.contains(token)) {
                    return "IGNORED";
                } else if (Block.addExact(token)) {
                    if (isReceiveRepass()) {
                        sendToOthers(token);
                        return "REPASSED";
                    } else {
                        sendToRepass(token);
                        return "ADDED";
                    }
                } else {
                    return "EXISTS";
                }
            } catch (ProcessException ex) {
                return ex.getErrorMessage();
            }
        } else {
            return "NOT FOUND";
        }
    }
    
    public TreeSet<String> release() {
        TreeSet<String> returnSet = new TreeSet<String>();
        for (String token : getRetationSet()) {
            String response = release(token);
            if (!response.equals("NOT FOUND")) {
                returnSet.add(token + " => " + response);
            }
        }
        return returnSet;
    }
    
    public static TreeSet<String> releaseAll() {
        TreeSet<String> returnSet = new TreeSet<String>();
        for (Peer peer : Peer.getSet()) {
            for (String response : peer.release()) {
                returnSet.add(peer.getAddress() + ':' + response);
            }
        }
        return returnSet;
    }
    
    public static TreeSet<String> releaseAll(String token) {
        TreeSet<String> returnSet = new TreeSet<String>();
        for (Peer peer : Peer.getSet()) {
            String response = peer.release(token);
            returnSet.add(peer.getAddress() + ':' + response);
        }
        return returnSet;
    }
    
    /**
     * Mapa de usuário com busca de hash O(1).
     */
    private static final HashMap<String,Peer> MAP = new HashMap<String,Peer>();
    
    /**
     * Flag que indica se o cache foi modificado.
     */
    private static boolean CHANGED = false;
    
    public boolean drop() {
        return Peer.drop(address) != null;
    }
    
    public Peer clone(String hostname) throws ProcessException {
        Peer peerNew = create(hostname, port);
        peerNew.send = this.send;
        peerNew.receive = this.receive;
        peerNew.email = this.email;
        peerNew.limit = this.limit;
        peerNew.frequency = this.frequency.replicate();
        peerNew.last = this.last;
        return peerNew;
    }
    
    public synchronized static Peer create(
            String hostname, String port
            ) throws ProcessException {
        try {
            int portInt = Integer.parseInt(port);
            return create(hostname, portInt);
        } catch (NumberFormatException ex) {
            throw new ProcessException("ERROR: INVALID PORT", ex);
        }
    }
    
    public synchronized static Peer create(
            String hostname, int port
            ) throws ProcessException {
        hostname = Domain.extractHost(hostname, false);
        if (MAP.containsKey(hostname)) {
            return null;
        } else {
            Peer peer = new Peer(hostname, port);
            MAP.put(hostname, peer);
            CHANGED = true;
            return peer;
        }
    }
    
    public synchronized static TreeSet<Peer> getSet() {
        TreeSet<Peer> peerSet = new TreeSet<Peer>();
        peerSet.addAll(MAP.values());
        return peerSet;
    }
    
    public static TreeSet<Peer> getSendAllSet() {
        TreeSet<Peer> peerSet = new TreeSet<Peer>();
        for (Peer peer : getSet()) {
            switch (peer.getSendStatus()) {
                case BLOCK:
                case ALWAYS:
                case REPASS:
                    peerSet.add(peer);
                    break;
            }
        }
        return peerSet;
    }
    
    public static TreeSet<Peer> getReputationSet() {
        TreeSet<Peer> peerSet = new TreeSet<Peer>();
        for (Peer peer : getSet()) {
            switch (peer.getSendStatus()) {
                case BLOCK:
                case REPUTATION:
                case ALWAYS:
                case REPASS:
                case MIRROR:
                    peerSet.add(peer);
                    break;
            }
        }
        return peerSet;
    }
    
    public TreeSet<Peer> getSendSet() {
        TreeSet<Peer> peerSet = new TreeSet<Peer>();
        for (Peer peer : getSet()) {
            if (!peer.equals(this)) {
                switch (peer.getSendStatus()) {
                    case BLOCK:
                    case ALWAYS:
                    case REPASS:
                        peerSet.add(peer);
                        break;
                }
            }
        }
        return peerSet;
    }
    
    public TreeSet<Peer> getRepassSet() {
        TreeSet<Peer> peerSet = new TreeSet<Peer>();
        for (Peer peer : getSet()) {
            if (!peer.equals(this)) {
                switch (peer.getSendStatus()) {
                    case REPASS:
                        peerSet.add(peer);
                        break;
                }
            }
        }
        return peerSet;
    }
    
    public static TreeSet<Peer> getMirrorSet() {
        TreeSet<Peer> peerSet = new TreeSet<Peer>();
        for (Peer peer : getSet()) {
            if (peer.getSendStatus() == Send.MIRROR) {
                peerSet.add(peer);
            }
        }
        return peerSet;
    }
    
    public static TreeSet<Peer> dropAll() {
        TreeSet<Peer> peerSet = new TreeSet<Peer>();
        for (Peer peer : getSet()) {
            if (peer.drop()) {
                peerSet.add(peer);
            }
        }
        return peerSet;
    }
    
    public synchronized static Peer drop(String address) {
        Peer peer = MAP.remove(address);
        if (peer != null) {
            CHANGED = true;
        }
        return peer;
    }
    
    public synchronized static Peer get(String address) {
        if (address == null) {
            return null;
        } else {
            return MAP.get(address);
        }
    }
    
    public static Peer get(InetAddress inetAddress) {
        String ip = inetAddress.getHostAddress();
        ip = Subnet.normalizeIP(ip);
        for (Peer peer : getSet()) {
            try {
                if (ip.equals(peer.getIP())) {
                    return peer;
                }
            } catch (UnknownHostException ex) {
                Server.logDebug("peer '" + peer.getAddress() + "' has unknown host.");
            }
        }
        return null;
    }
    
    public static synchronized HashMap<String,Peer> getMap() {
        HashMap<String,Peer> map = new HashMap<String,Peer>();
        for (Peer peer : MAP.values()) {
            Peer clone = new Peer(peer);
            map.put(clone.getAddress(), clone);
        }
        return map;
    }
    
//    public void sendAll() {
//        if (Core.hasPeerConnection()) {
//            TreeMap<String,Distribution> distributionSet = SPF.getDistributionMap();
//            for (String token : distributionSet.keySet()) {
//                Distribution distribution = distributionSet.get(token);
//                if (distribution.isBlocked(token)) {
//                    send(token);
//                }
//                try {
//                    Thread.sleep(100);
//                } catch (InterruptedException ex) {
//                    // Interrompido.
//                }
//            }
//        }
//    }
    
    public void send(String token) {
        if (Core.hasPeerConnection()) {
            long time = System.currentTimeMillis();
            String origin = null;
            String address = getAddress();
            int port = getPort();
            String result = Core.sendCommandToPeer(token, address, port);
            Server.logPeerSend(time, origin, token, result);
        }
    }
    
    public void sendToOthers(String token) {
        long time = System.currentTimeMillis();
        if (Core.hasPeerConnection()) {
            String origin = null;
            String result = "SENT";
            try {
                for (Peer peer : getSendSet()) {
                    String address = peer.getAddress();
                    int port = peer.getPort();
                    Core.sendCommandToPeer(token, address, port);
                }
            } catch (Exception ex) {
                result = ex.getMessage();
            }
            Server.logPeerSend(time, origin, token, result);
        }
    }
    
    public static void sendToAll(String token) {
        long time = System.currentTimeMillis();
        if (Core.hasPeerConnection()) {
            String origin = null;
            String result = "SENT";
            try {
                for (Peer peer : getSendAllSet()) {
                    String address = peer.getAddress();
                    int port = peer.getPort();
                    Core.sendCommandToPeer(token, address, port);
                }
            } catch (Exception ex) {
                result = ex.getMessage();
            }
            Server.logPeerSend(time, origin, token, result);
        }
    }
    
    public static void sendBlockToAll(String token) {
        if (isValid(token)) {
            long time = System.currentTimeMillis();
            if (Core.hasPeerConnection()) {
                String origin = null;
                String result = "SENT";
                String command = "BLOCK " + token;
                try {
                    for (Peer peer : getSendAllSet()) {
                        String address = peer.getAddress();
                        int port = peer.getPort();
                        Core.sendCommandToPeer(command, address, port);
                    }
                } catch (Exception ex) {
                    result = ex.getMessage();
                }
                Server.logPeerSend(time, origin, command, result);
            }
        }
    }
    
    /**
     * Método de transição.
     * @param token
     * @param distribuiton 
     */
    public static void sendToAll(String token, Distribution distribuiton) {
        if (isValid(token)) {
            long time = System.currentTimeMillis();
            if (Core.hasPeerConnection()) {
                int[] binomial;
                if (distribuiton == null) {
                    binomial = new int[2];
                } else {
                    binomial = distribuiton.getBinomial();
                }
                int ham = binomial[0];
                int spam = binomial[1];
                if (spam % 3 == 0) {
                    String origin = null;
                    String result = "SENT";
                    String command = "REPUTATION " + token + " " + ham + " " + spam;
                    try {
                        for (Peer peer : getReputationSet()) {
                            String address = peer.getAddress();
                            int port = peer.getPort();
                            Core.sendCommandToPeer(command, address, port);
                        }
                    } catch (Exception ex) {
                        result = ex.getMessage();
                    }
                    Server.logPeerSend(time, origin, command, result);
                }
            }
        }
    }
    
    public boolean sendHELO() {
        String connection = Core.getPeerConnection();
        if (connection == null) {
            return false;
        } else {
            String origin = null;
            String email = Core.getAdminEmail();
            String helo = "HELO " + connection + (email == null ? "" : " " + email);
            long time = System.currentTimeMillis();
            String address = getAddress();
            int port = getPort();
            String result = Core.sendCommandToPeer(helo, address, port);
            Server.log(time, Core.Level.DEBUG, "PEERP", origin, helo, result);
            return true;
        }
    }
    
    public static void sendHeloToAll() {
        long time = System.currentTimeMillis();
        String connection = Core.getPeerConnection();
        if (connection != null) {
            String origin = null;
            String result = "SENT";
            String email = Core.getAdminEmail();
            String helo = "HELO " + connection + (email == null ? "" : " " + email);
            try {
                for (Peer peer : getSendAllSet()) {
                    String address = peer.getAddress();
                    int port = peer.getPort();
                    Core.sendCommandToPeer(helo, address, port);
                }
            } catch (Exception ex) {
                result = ex.getMessage();
            }
            Server.log(time, Core.Level.DEBUG, "PEERP", origin, helo, result);
        }
    }
    
    public void sendToRepass(String token) {
        long time = System.currentTimeMillis();
        if (Core.hasPeerConnection()) {
            String origin = null;
            String result = "SENT";
            try {
                for (Peer peer : getRepassSet()) {
                    String address = peer.getAddress();
                    int port = peer.getPort();
                    Core.sendCommandToPeer(token, address, port);
                }
            } catch (Exception ex) {
                result = ex.getMessage();
            }
            Server.logPeerSend(time, origin, token, result);
        }
    }
    
    private static boolean isValid(String token) {
        if (token == null || token.length() == 0) {
            return false;
        } else if (Subnet.isValidIP(token)) {
            return false;
        } else if (token.startsWith(".") && Domain.isHostname(token.substring(1))) {
            return true;
        } else if (token.contains("@") && Domain.isEmail(token)) {
            return true;
        } else if (token.startsWith("@") && Domain.containsDomain(token.substring(1))) {
            return true;
        } else {
            return false;
        }
    }
    
    public static void sendToMirros(String command) {
        long time = System.currentTimeMillis();
        if (Core.hasPeerConnection()) {
            String origin = null;
            String result = "SENT";
            try {
                for (Peer mirror : getMirrorSet()) {
                    String address = mirror.getAddress();
                    int port = mirror.getPort();
                    Core.sendCommandToPeer(command, address, port);
                }
            } catch (Exception ex) {
                result = ex.getMessage();
            }
            Server.logPeerSend(time, origin, command, result);
        }
    }
    
    private boolean isReceiveDrop() {
        return receive == Receive.DROP;
    }
    
    private boolean isReceiveReject() {
        return receive == Receive.REJECT;
    }
    
    private boolean isReceiveRetain() {
        return receive == Receive.RETAIN;
    }
    
    private boolean isReceiveRepass() {
        return receive == Receive.REPASS;
    }
    
    private synchronized boolean addRetain(String token) {
        return retainSet.add(token);
    }
    
    public String processReceive(String token) {
        try {
            if (!isValid(token)) {
                return "INVALID";
            } else if (Domain.isReserved(token)) {
                return "RESERVED";
            } else if (Ignore.contains(token)) {
                return "IGNORED";
            } else if (isReceiveReject()) {
                return "REJECTED";
            } else if (isReceiveDrop()) {
                return "DROPPED";
            } else if (isReceiveRetain()) {
                if (addRetain(token)) {
                    return "RETAINED";
                } else {
                    return "DROPPED";
                }
            } else if (Block.addExact(token)) {
                if (isReceiveRepass()) {
                    sendToOthers(token);
                    return "REPASSED";
                } else {
                    sendToRepass(token);
                    return "ADDED";
                }
            } else {
                return "EXISTS";
            }
        } catch (Exception ex) {
            Server.logError(ex);
            return ex.getMessage();
        }
    }
    
    public String processBlock(String token) {
        try {
            if ((token = SPF.normalizeTokenFull(token)) == null) {
                return "INVALID";
            } else if (Ignore.contains(token)) {
                return "IGNORED";
            } else if (isReceiveReject()) {
                return "REJECTED";
            } else if (isReceiveDrop()) {
                return "DROPPED";
            } else if (Block.containsExact(token)) {
                return "EXISTS";
            } else if (isReceiveRetain()) {
                if (addRetain(token)) {
                    return "RETAINED";
                } else {
                    return "DROPPED";
                }
            } else if (Block.addExact(token)) {
                if (isReceiveRepass()) {
                    sendToOthers(token);
                    return "REPASSED";
                } else {
                    sendToRepass(token);
                    return "ADDED";
                }
            } else {
                return "EXISTS";
            }
        } catch (Exception ex) {
            Server.logError(ex);
            return ex.getMessage();
        }
    }
    
    public static void store() {
        if (CHANGED) {
            try {
                long time = System.currentTimeMillis();
                HashMap<String,Peer> map = getMap();
                File file = new File("./data/peer.map");
                FileOutputStream outputStream = new FileOutputStream(file);
                try {
                    SerializationUtils.serialize(map, outputStream);
                    // Atualiza flag de atualização.
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
    
    public static synchronized void load() {
        long time = System.currentTimeMillis();
        File file = new File("./data/peer.map");
        if (file.exists()) {
            try {
                HashMap<Object,Object> map;
                FileInputStream fileInputStream = new FileInputStream(file);
                try {
                    map = SerializationUtils.deserialize(fileInputStream);
                } finally {
                    fileInputStream.close();
                }
                for (Object key : map.keySet()) {
                    Object value = map.get(key);
                    if (key instanceof InetAddress) {
                        InetAddress address = (InetAddress) key;
                        Integer port = (Integer) value;
                        create(address.getHostAddress(), port);
                    } else if (key instanceof String) {
                        String address = (String) key;
                        if (value instanceof Integer) {
                            Integer port = (Integer) value;
                            create(address, port);
                        } else if (value instanceof Peer) {
                            Peer peer = (Peer) value;
                            if (peer.send == Send.DUNNO) {
                                // Obsoleto.
                                peer.send = Send.NEVER;
                            } else if (peer.send == Send.BLOCK) {
                                // Obsoleto.
                                peer.send = Send.ALWAYS;
                            }
                            if (peer.receive == Receive.CONFIRM) {
                                // Obsoleto.
                                peer.receive = Receive.RETAIN;
                            }
                            if (peer.retainSet == null) {
                                peer.retainSet = new TreeSet<String>();
                            }
                            if (peer.limit == 0) {
                                peer.limit = 100;
                            }
                            if (peer.reputationMap2 == null) {
                                peer.reputationMap2 = new TreeMap<String,Binomial>();
                            }
                            if (peer.reputationMap != null) {
                                peer.reputationMap2.putAll(peer.reputationMap);
                                peer.reputationMap.clear();
                            }
                            MAP.put(address, peer);
                        }
                    }
                }
                Server.logLoad(time, file);
            } catch (Exception ex) {
                Server.logError(ex);
            }
        }
    }
    
    public boolean hasEmail() {
        return email != null;
    }
    
    public boolean hasFrequency() {
        return frequency != null;
    }
    
    public long getIdleTimeMillis() {
        if (last == 0) {
            return 0;
        } else {
            return System.currentTimeMillis() - last;
        }
    }
    
    public String getFrequencyLiteral() {
        if (hasFrequency()) {
            int frequencyInt = frequency.getMaximumInt();
            long idleTimeInt = getIdleTimeMillis();
            if (idleTimeInt > frequencyInt * 5 && idleTimeInt > 3600000) {
                return "DEAD";
            } else {
                char sinal = '~';
                if (frequencyInt < limit) {
                    frequencyInt = limit;
                    sinal = '<';
                } else if (idleTimeInt > frequencyInt * 3) {
                    sinal = '>';
                }
                if (frequencyInt >= 3600000) {
                    return sinal + ((frequencyInt / 3600000) + "h");
                } else if (frequencyInt >= 60000) {
                    return sinal + ((frequencyInt / 60000) + "min");
                } else if (frequencyInt >= 1000) {
                    return sinal + ((frequencyInt / 1000) + "s");
                } else {
                    return sinal + (frequencyInt + "ms");
                }
            }
        } else {
            return "UNDEFINED";
        }
    }
    
    private Float getInterval() {
        long current = System.currentTimeMillis();
        Float interval;
        if (last == 0) {
            interval = null;
        } else {
            interval = (float) (current - last);
        }
        last = current;
        return interval;
    }
    
    public void addNotification() {
        Float interval = getInterval();
        if (interval == null) {
            // Se não houver intervalo definido, fazer nada.
        } else if (frequency == null) {
            frequency = new NormalDistribution(interval);
        } else {
            frequency.addElement(interval);
        }
    }
    
    @Override
    public int hashCode() {
        return address.hashCode();
    }
    
    @Override
    public boolean equals(Object o) {
        if (o instanceof Peer) {
            Peer other = (Peer) o;
            return this.address.equals(other.address);
        } else {
            return false;
        }
    }
    
    @Override
    public int compareTo(Peer other) {
        if (other == null) {
            return -1;
        } else {
            return this.toString().compareTo(other.toString());
        }
    }
    
    @Override
    public String toString() {
        User user = getUser();
        if (user == null) {
            return address + ":" + port
                    + (send == null ? "" : " " + send.name())
                    + (receive == null ? "" : " " + receive.name())
                    + (retainSet == null ? "" : " " + retainSet.size())
                    + " " + getFrequencyLiteral()
                    + (email == null ? "" : " <" + email + ">");
        } else {
            return address + ":" + port
                    + (send == null ? "" : " " + send.name())
                    + (receive == null ? "" : " " + receive.name())
                    + (retainSet == null ? "" : " " + retainSet.size())
                    + " " + getFrequencyLiteral()
                    + " " + user;
        }
    }
    
    protected synchronized String setReputation(
            String key,
            String ham,
            String spam
    ) {
        try {
            if (!isValid(key)) {
                return "INVALID";
            } else if (Domain.isReserved(key)) {
                return "RESERVED";
            } else if (isReceiveReject()) {
                return "REJECTED";
            } else if (isReceiveDrop()) {
                return "DROPPED";
            } else {
                int hamInt = Integer.parseInt(ham);
                int spamInt = Integer.parseInt(spam);
                Binomial binomial;
                if (hamInt == 0 && spamInt == 0) {
                    binomial = reputationMap2.remove(key);
                    if (binomial == null) {
                        return "NOT FOUND";
                    } else {
                        CHANGED = true;
                        return "DROPPED";
                    }
                } else if ((binomial = reputationMap2.get(key)) == null) {
                    binomial = new Binomial(hamInt, spamInt);
                    reputationMap2.put(key, binomial);
                    if (Ignore.contains(key)) {
                        binomial.clear();
                        return "IGNORED";
                    } else {
                        return "ADDED";
                    }
                } else {
                    binomial.set(hamInt, spamInt);
                    if (Ignore.contains(key)) {
                        binomial.clear();
                        return "IGNORED";
                    } else {
                        return "UPDATED";
                    }
                }
            }
        } catch (Exception ex) {
            return "INVALID";
        }
    }
    
    public boolean isExpired7() {
        return System.currentTimeMillis() - last > 604800000;
    }
    
    public static void dropExpired() {
        String origin = null;
        for (Peer peer : getSet()) {
            long time = System.currentTimeMillis();
            if (peer.isExpired7()) {
                if (peer.drop()) {
                    Server.log(time, Core.Level.INFO, "PEERH", origin, peer.getAddress(), "EXPIRED");
                }
            } else {
                TreeMap<String,Binomial> reputationMap = peer.getReputationMap();
                for (String key : reputationMap.keySet()) {
                    time = System.currentTimeMillis();
                    Binomial binomial = reputationMap.get(key);
                    if (binomial.isExpired3()) {
                        binomial = peer.dropReputation(key);
                        if (binomial != null) {
                            Server.log(time, Core.Level.INFO, "PEERR", peer.getAddress(), key, "EXPIRED");
                        }
                    }
                }
            }
        }
    }
    
    public static TreeSet<String> clearAllReputation(String key) {
        TreeSet<String> clearSet = new TreeSet<String>();
        for (Peer peer : getSet()) {
            for (String token : peer.getAllReputations(key)) {
                if (peer.clearReputation(token)) {
                    clearSet.add(token);
                }
            }
        }
        return clearSet;
    }
    
    private synchronized boolean containsReputationExact(String key) {
        return reputationMap2.containsKey(key);
    }
    
    private synchronized Set<String> subSet(String begin, String end) {
        TreeSet<String> subSet = new TreeSet<String>();
        NavigableMap<String,Binomial> subMap = reputationMap2.subMap(begin, false, end, false);
        subSet.addAll(subMap.keySet());
        return subSet;
    }
    
    private TreeSet<String> getAllReputations(String value) {
        TreeSet<String> blockSet = new TreeSet<String>();
        if (Subnet.isValidIP(value)) {
            String ip = Subnet.normalizeIP(value);
            if (containsReputationExact(ip)) {
                blockSet.add(ip);
            }
        } else if (Subnet.isValidCIDR(value)) {
            String cidr = Subnet.normalizeCIDR(value);
            for (String ip : subSet("0", ":")) {
                if (Subnet.containsIP(cidr, ip)) {
                    blockSet.add(ip);
                }
            }
            for (String ip : subSet("a", "g")) {
                if (SubnetIPv6.containsIP(cidr, ip)) {
                    blockSet.add(ip);
                }
            }
        } else if (value.startsWith(".")) {
            String hostname = value;
            for (String key : subSet(".", "/")) {
                if (key.endsWith(hostname)) {
                    blockSet.add(key);
                }
            }
            for (String mx : subSet("@", "A")) {
                String hostKey = '.' + mx.substring(1);
                if (hostKey.endsWith(hostname)) {
                    blockSet.add(hostKey);
                }
            }
        } else if (containsReputationExact(value)) {
            blockSet.add(value);
        }
        return blockSet;
    }
    
    private synchronized Binomial dropReputation(String key) {
        return reputationMap2.remove(key);
    }
    
    private synchronized boolean clearReputation(String key) {
        Binomial binomial = reputationMap2.get(key);
        if (binomial == null) {
             return false;
        } else {
             return binomial.clear();
        }
    }
    
    /**
     * Classe que representa a distribuição binomial entre HAM e SPAM.
     */
    public static final class Binomial implements Serializable {

        private static final long serialVersionUID = 1L;
        
        private int ham; // Quantidade total de HAM em sete dias.
        private int spam; // Quantidade total de SPAM em sete dias
        private long last = System.currentTimeMillis();
        private final Status status;
        
        public Binomial(int ham, int spam) throws ProcessException {
            this.status = null;
            set(ham, spam);
        }
        
        public Binomial(Status status) throws ProcessException {
            this.status = status;
            set(0, 0);
        }
        
        public synchronized void set(int ham, int spam) throws ProcessException {
            if (ham < 0) {
                throw new ProcessException("ERROR: INVALID HAM VALUE");
            } else if (spam < 0) {
                throw new ProcessException("ERROR: INVALID SPAM VALUE");
            } else if (this.ham != ham || this.spam != spam) {
                this.ham = ham;
                this.spam = spam;
                this.last = System.currentTimeMillis();
                CHANGED = true;
            }
        }
        
        public synchronized int getSPAM() {
            return spam;
        }
        
        public synchronized int getHAM() {
            return ham;
        }
        
        public synchronized Status getStatus() {
            return status;
        }
        
        public synchronized boolean clear() {
            if (spam > 0) {
                this.ham += spam;
                this.spam = 0;
                CHANGED = true;
                return true;
            } else {
                return false;
            }
        }
        
        public synchronized boolean isExpired3() {
            return System.currentTimeMillis() - last > 259200000;
        }

        public synchronized boolean isExpired7() {
            return System.currentTimeMillis() - last > 604800000;
        }

        public synchronized boolean hasLastUpdate() {
            return last > 0;
        }
        
        public synchronized float getSpamProbability() {
            if (isExpired7()) {
                return 0.0f;
            } else if (ham + spam == 0) {
                return 0.0f;
            } else {
                return (float) spam / (float) (ham + spam);
            }
        }
        
        @Override
        public String toString() {
            return Float.toString(getSpamProbability());
        }
    }
}

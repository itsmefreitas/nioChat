import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

public class ChatServer
{
  // A pre-allocated buffer for the received data
  static private final ByteBuffer buffer = ByteBuffer.allocate( 16384 );
  
  // Decoder for incoming text -- assume UTF-8
  static private final Charset charset = Charset.forName("UTF8");
  static private final CharsetDecoder decoder = charset.newDecoder();
  
  // Private class for Client, providing Constructor for Client and default values;
  static private class Client
  {
    
    public boolean init;
    public boolean inside;
    
    public String nick;
    public String room;
    
    public StringBuffer buffer;
    
    public Client()
    {
      this.init = true;
      this.inside = false;
      
      this.nick = "";
      this.room = "";
      
      this.buffer = new StringBuffer();
    }
    
  }
  
  // HashMap that will contain all connected Client objects at a given point in time.
  static private final HashMap<SocketChannel, Client> clients = new HashMap<>();
  
  // HashMap with String keys for the different chat rooms and their 'online' SocketChannels.
  static private final HashMap<String, ArrayList<SocketChannel>> chatRooms = new HashMap<>();
  
  static private Scanner cmdScanner;
  
  static public void main( String args[] ) throws Exception
  {
    
    // Parse port from command line
    int port = Integer.parseInt( args[0] );
    
    try
    {
      // Instead of creating a ServerSocket, create a ServerSocketChannel
      ServerSocketChannel ssc = ServerSocketChannel.open();
      
      // Set it to non-blocking, so we can use select
      ssc.configureBlocking( false );
      
      // Get the Socket connected to this channel, and bind it to the
      // listening port
      ServerSocket ss = ssc.socket();
      InetSocketAddress isa = new InetSocketAddress( port );
      ss.bind( isa );
      
      // Create a new Selector for selecting
      Selector selector = Selector.open();
      
      // Register the ServerSocketChannel, so we can listen for incoming
      // connections
      ssc.register( selector, SelectionKey.OP_ACCEPT );
      System.out.println( "Listening on port "+port );
      
      while (true)
      {
        // See if we've had any activity -- either an incoming connection,
        // or incoming data on an existing connection
        int num = selector.select();
        
        // If we don't have any activity, loop around and wait again
        if (num == 0)
        {
          continue;
        }
        
        // Get the keys corresponding to the activity that has been
        // detected, and process them one by one
        Set<SelectionKey> keys = selector.selectedKeys();
        Iterator<SelectionKey> it = keys.iterator();
        
        while (it.hasNext())
        {
          // Get a key representing one of bits of I/O activity
          SelectionKey key = it.next();
          
          // What kind of activity is it?
          if ((key.readyOps() & SelectionKey.OP_ACCEPT) == SelectionKey.OP_ACCEPT)
          {
          
            // It's an incoming connection.  Register this socket with
            // the Selector so we can listen for input on it
            Socket s = ss.accept();
            System.out.println( "Got connection from "+s );
            
            // Make sure to make it non-blocking, so we can use a selector
            // on it.
            SocketChannel sc = s.getChannel();
            sc.configureBlocking( false );
            
            // Register it with the selector, for reading
            sc.register( selector, SelectionKey.OP_READ );
            
            // Add another object to the HashMap containing the newly connected client.
            clients.put(sc, new Client());
            
          }
          
          else if ((key.readyOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ)
          {
          
            SocketChannel sc = null;
            
            try
            {
              
              // It's incoming data on a connection -- process it
              sc = (SocketChannel)key.channel();
              
              boolean ok = processInput(sc);
              
              // If the connection is dead, remove it from the selector
              // and close it
              if (!ok)
              {
                key.cancel();
                
                Socket s = null;
                
                try
                {
                  s = sc.socket();
                  
                  Client c = clients.get(sc);
                  
                  if (c.inside)
                  {
                    // If the client attached to the SocketChannel was in a chat room, we ought to leave it first.
                    leaveRoom(sc, c);
                  }
                  
                  // We can now remove the client c, with key SocketChannel sc, from the clients HashMap
                  clients.remove(sc);
                  
                  System.out.println( "Closing connection to "+s );
                  s.close();
                }
                
                catch( IOException ie )
                {
                  System.err.println( "Error closing socket "+s+": "+ie );
                }
              }
              
            }
            
            catch( IOException ie )
            {
              
              // On exception, remove this channel from the selector
              key.cancel();
              
              try
              {
                sc.close();
                clients.remove(sc);
              }
              
              catch( IOException ie2 )
              {
                System.out.println( ie2 );
              }
              
              System.out.println( "Closed "+sc );
            }
          }
        }
        
        // We remove the selected keys, because we've dealt with them.
        keys.clear();
      }
    }
    catch( IOException ie )
    {
      System.err.println( ie );
    }
  }
  
  // Write to server buffer from the inbound data SocketChannel provides.
  static private boolean processInput(SocketChannel sc) throws IOException
  {
   
    //Get buffer ready to be written to.
    buffer.clear();
    
    sc.read(buffer);
    buffer.flip();
    
    if (buffer.limit() == 0)
    {
      return false;
    }
    
    String message = decoder.decode(buffer).toString();
    
    Client c = clients.get(sc);
    
    StringBuffer command = c.buffer;
    
    // Append to buffer until we recieve LF (line feed) from that SocketChannel
    if (!message.endsWith("\n"))
    {
      c.buffer.append(message);
    }
    
    else
    {
      
      command.append(message);
      
      message = command.toString();
      
      command = new StringBuffer();
      c.buffer = command;
      
      // If message starts as '/'[a-z]+ then it's possibly a command
      if (message.charAt(0) == '/' && message.charAt(1) != '/')
      {
        
        cmdScanner = new Scanner(message);
        
        String cmd = cmdScanner.next();
        
        switch (cmd)
        {
          case "/nick":
            
            if (!cmdScanner.hasNext())
            {
              // Too few arguments
              sendStatus(sc, "ERROR\n");
              break;
            }
            
            changeNick(sc, c, cmdScanner.next());
            
            break;
            
          case "/join":
          
            if (!cmdScanner.hasNext())
            {
              // Too few arguments
              sendStatus(sc, "ERROR\n");
              break;
            }
            
            joinRoom(sc, c, cmdScanner.next());
          
            break;
            
          case "/leave":
          
            if (cmdScanner.hasNext())
            {
              // Too many arguments
              sendStatus(sc, "ERROR\n");
              break;
            }
          
            leaveRoom(sc, c);
          
            break;
            
          case "/bye":
          
            if (cmdScanner.hasNext())
            {
              // Too many arguments
              sendStatus(sc, "ERROR\n");
              break;
            }
            
            if (c.inside)
            {
              leaveRoom(sc, c);
            }
            
            leave(sc, c);
            
            break;
            
          case "/priv":
            
            if (!cmdScanner.hasNext())
            {
              // Too few arguments
              sendStatus(sc, "ERROR\n");
              break;
              
            }
            
            // The destination of the private message is cmdScanner's next() value.
            String to = cmdScanner.next();
            
            // Stripping String message of '/priv'
            String nMessage = message.substring(message.indexOf(to), message.length()-1);
            
            if (nMessage.length() <= to.length())
            {
              // Message content empty
              sendStatus(sc, "ERROR\n");
              break;
            }
            
            // Trim the command-less string so that only the message is saved.
            nMessage = nMessage.substring(to.length()+1, nMessage.length());
            
            sendPriv(sc, c, to, nMessage+"\n");
            
            break;
            
          default:
          
            // When it isn't a valid command
            sendStatus(sc, "ERROR\n");
          
            break;
        }
        
      }
      
      // If nothing has been escaped or cmd doesn't start with a '/' -> it is a message
      else
      {
        sendToRoom(sc, c, "MESSAGE "+c.nick+" "+message);
      }
    }
    
    return true;
  }
  
  static private void sendStatus(SocketChannel sc, String status) throws IOException
  {
    
    ByteBuffer bb = ByteBuffer.wrap(status.getBytes());
    sc.write(bb);
    
  }
  
  static private void changeNick(SocketChannel sc, Client c, String newNick) throws IOException
  {
    
    // Iterate over clients HashMap to make sure no other client has the same value for property nick as the newNick
    for (Map.Entry<SocketChannel, Client> entry : clients.entrySet())
    {
      
      String u = entry.getValue().nick;
      
      if (newNick.compareTo(u) == 0)
      {
        // Nickname already in use!
        sendStatus(sc, "ERROR\n");
        return;
      }
    }
    
    // If the client didn't have a nickname yet, now it does, hence init status = false
    if (c.init)
    {
      c.init = false;
      
    }
    
    else if (c.inside)
    {
      //Notify all the other users that share a chatroom with the Client c when it changes nickname
      ArrayList<SocketChannel> usersInRoom = chatRooms.get(c.room);
      
      notifyUsers(sc, usersInRoom, "NEWNICK "+c.nick+" "+newNick+"\n");
    }
    
    // Update value for object attribute.
    c.nick = newNick;
    
    // Replace HashMap entry for Client c - to make sure DeepCopy is performed(?)
    clients.replace(sc, c);
    
    // Inform the client SocketChannel that the nickname change/registration has been correctly processed
    sendStatus(sc, "OK\n");
    
  }
  
  static private void joinRoom(SocketChannel sc, Client c, String roomName) throws IOException
  {
    // If client is init, it means no nick has yet been assigned to it.
    if (c.init)
    {
      // Init error.
      sendStatus(sc, "ERROR\n");
      return;
    }
    
    // If the client was previously in a chat room, then we ought to leave it.
    else if (c.inside)
    {
      //Leave a chatroom here.
      leaveRoom(sc, c);
    }
    
    // What other users are in the chat room = roomName?
    ArrayList<SocketChannel> usersInRoom = chatRooms.get(roomName);
    
    // If there are none, we create a new chat room and add it to chatRooms HashMap
    if (usersInRoom == null)
    {
      usersInRoom = new ArrayList<>();
      chatRooms.put(roomName, usersInRoom);
    }
    
    else if (usersInRoom.size() > 0)
    {
      // Notify other users in room that I have joined.
      notifyUsers(sc, usersInRoom, "JOINED "+c.nick+"\n");
    }
    
    // Add the new SocketChannel to the ArrayList usersInRoom
    usersInRoom.add(sc);
    
    // Now the client is inside a chat room and it's c.room property is roomName
    c.room = roomName;
    c.inside = true;
    
    // Report back to client that it joined a chat room sucessfully.
    sendStatus(sc, "OK\n");
    
  }
  
  static private void leaveRoom(SocketChannel sc, Client c) throws IOException
  {
    // If there are no rooms to leave, then we return an error.
    if (!c.inside || c.init)
    {
      // We are not in a chat room, so there's no leaving it in the first place.
      sendStatus(sc, "ERROR\n");
      return;
    }
    
    // Get all the connected clients (SocketChannel) that have the same c.room.
    ArrayList<SocketChannel> users = chatRooms.get(c.room);
    
    // Remove the Client from c.room (ArrayList<SocketChannel> users)
    users.remove(sc);
    
    // Client is no longer in a chat room.
    c.inside = false;
    c.room = "";
    
    // If there are other users in room, then we notify them someone left.
    if (users.size() > 0)
    {
      notifyUsers(sc, users, "LEFT "+c.nick+"\n");
    }
    
    // If the room is now empty, then we remove it from the chatRooms HashMap...
    else
    {
      chatRooms.remove(c.room);
    }
    
    // If all goes well, client gets an OK status message to be sure it left the chat room.
    sendStatus(sc, "OK\n");
  }
  
  // When a message is to be sent to all the users in a chat room, except the user itself, hence passing SocketChannel me.
  static private void notifyUsers(SocketChannel me, ArrayList<SocketChannel> users, String message) throws IOException
  {
    
    for (SocketChannel u : users)
    {
      if (!u.equals(me))
      {
        ByteBuffer bb = ByteBuffer.wrap(message.getBytes());
        
        u.write(bb);
      }
    }
    
  }
  
  // Disconnect client from the server, make it go poof!
  static private void leave(SocketChannel sc, Client c) throws IOException
  {
    
    SocketChannel toRemove = null;
    
    // Iterating over clients array so we can find which SocketChannel to close.
    
    for (Map.Entry<SocketChannel, Client> entry : clients.entrySet())
    {
      
      SocketChannel chClient = entry.getKey();
      
      if (chClient.equals(sc))
      {
        toRemove = sc;
        break;
      }
      
    }
    
    // Getting the attached Socket to the SocketChannel so it can be closed.
    Socket s = toRemove.socket();
    
    System.out.println( "Closing connection to "+s );
    
    // Sending BYE status message to the SocketChannel to be removed from HashMap clients
    sendStatus(toRemove, "BYE\n");
    clients.remove(toRemove);
    
    s.close();
    
  }
  
  static private void sendToRoom(SocketChannel sc, Client c, String message) throws IOException
  {
    
    // If the user is not in a room or does not have a nickname, we cannot send anything to any room.
    if (!c.inside || c.init)
    {
      sendStatus(sc, "ERROR\n");
      return;
    }
    
    ArrayList<SocketChannel> usersInRoom = chatRooms.get(c.room);
    
    // For all the SocketChannels attached to the chat room, we write to their corresponding SocketChannels.
    
    for (SocketChannel usr : usersInRoom)
    {
      ByteBuffer bb = ByteBuffer.wrap(message.getBytes());
      usr.write(bb);
    }
    
  }
  
  static private void sendPriv(SocketChannel sc, Client c, String to, String message) throws IOException
  {
    if (c.init)
    {
      //ERROR init, user does not have a nickname.
      sendStatus(sc, "ERROR\n");
      return;
    }
    
    SocketChannel toSocket = null;
    
    // Iterate over clients HashMap to find the corresponding SocketChannel to a Client such that c.nick == to.
    for(Map.Entry<SocketChannel, Client> entry : clients.entrySet())
    {
      Client t = entry.getValue();
      
      if (t.nick.compareTo(to) == 0)
      {
        toSocket = entry.getKey();
      }
      
    }
    
    if (toSocket != null)
    {
      
      // Compose the output to the corresponding SocketChannel
      String query = "PRIVATE "+c.nick+" "+message;
      
      ByteBuffer bb = ByteBuffer.wrap(query.getBytes());
      toSocket.write(bb);
      
      // Send the confirmation to the author of the message.
      sendStatus(sc, "OK\n");
      
      return;
    }
    
    // If we did not return from the function yet, then something has gone wrong and we have to report an error to the sender.
    sendStatus(sc, "ERROR\n");
    
  }
  
  // Reserved for debugging, sends message to all connected sockets, regardless of the chat room they're in.
  static private void sendMessage(SocketChannel sc, Client c, String message) throws IOException
  {
  
    for(Map.Entry<SocketChannel, Client> entry : clients.entrySet())
    {
      SocketChannel sock = entry.getKey();
      
      ByteBuffer bb = ByteBuffer.wrap(message.getBytes());
      
      sock.write(bb);
    }
    
  }
}

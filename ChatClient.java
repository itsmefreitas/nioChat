import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;


public class ChatClient
{

  // VariÃ¡veis relacionadas com a interface grÃ¡fica --- * NÃƒO MODIFICAR *
  JFrame frame = new JFrame("Chat Client");
  private JTextField chatBox = new JTextField();
  private JTextArea chatArea = new JTextArea();
  // --- Fim das variÃ¡veis relacionadas coma interface grÃ¡fica
  
  // Se for necessÃ¡rio adicionar variÃ¡veis ao objecto ChatClient, devem
  // ser colocadas aqui
  
  private static Socket sClient;
  
  private static DataOutputStream outputToServer;
  private static BufferedReader input;
  
  private static Scanner scanner;
  
  // MÃ©todo a usar para acrescentar uma string Ã  caixa de texto
  // * NÃƒO MODIFICAR *
  public void printMessage(final String message)
  {
    chatArea.append(message);
  }
  
  
  // Construtor
  public ChatClient(String server, int port) throws IOException
  {
  
    // InicializaÃ§Ã£o da interface grÃ¡fica --- * NÃƒO MODIFICAR *
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    JPanel panel = new JPanel();
    panel.setLayout(new BorderLayout());
    panel.add(chatBox);
    frame.setLayout(new BorderLayout());
    frame.add(panel, BorderLayout.SOUTH);
    frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
    frame.setSize(500, 300);
    frame.setVisible(true);
    chatArea.setEditable(false);
    chatBox.setEditable(true);
    chatBox.addActionListener(new ActionListener()
    {
      @Override
      
      public void actionPerformed(ActionEvent e)
      {
        try
        {
          newMessage(chatBox.getText());
        }
        catch (IOException ex)
        {
        }
        finally
        {
          chatBox.setText("");
        }
      }
    });
    // --- Fim da inicializaÃ§Ã£o da interface grÃ¡fica
    
    // Se for necessÃ¡rio adicionar cÃ³digo de inicializaÃ§Ã£o ao
    // construtor, deve ser colocado aqui
    
    ChatClient.sClient = new Socket(server, port);
    ChatClient.outputToServer = new DataOutputStream(sClient.getOutputStream());
    ChatClient.input = new BufferedReader(new InputStreamReader(sClient.getInputStream(), "UTF-8"));
  }
  
  
  // MÃ©todo invocado sempre que o utilizador insere uma mensagem
  // na caixa de entrada
  public void newMessage(String message) throws IOException
  {
    // PREENCHER AQUI com cÃ³digo que envia a mensagem ao servidor
    
    message += "\n";
    
    outputToServer.write(message.getBytes("UTF-8"));
    
  }
  
  
  // MÃ©todo principal do objecto
  public void run() throws IOException
  {
    // PREENCHER AQUI
    
    // Thread interface for the nonblocking input to server
    InputThread in = new InputThread();
    new Thread(in).start();
    
  }
  
  private class InputThread implements Runnable
  {
    @Override
    public void run()
    {
      try
      {
        String message, command="";
    
        while ((message = input.readLine()) != null)
        {
          
          scanner = new Scanner(message);
          
          printMessage(message+"\n");
          
        }
        
        new java.util.Timer().schedule( 
          new java.util.TimerTask() {
            @Override
            public void run() {
              System.exit(0);
            }
          }, 
          5000 
        );
      }
      catch (IOException e)
      {
        e.printStackTrace();
      }
    }
  }
  
  // Instancia o ChatClient e arranca-o invocando o seu mÃ©todo run()
  // * NÃƒO MODIFICAR *
  public static void main(String[] args) throws IOException
  {
    ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
    client.run();
  }

}

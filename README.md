#  BACnet OpenMUC Demo Application

Welcome to the **BACnet-OpenMUC Integration App**, a hands-on Java OSGi application that simulates a BACnet server and client, demonstrating real-time communication between devices!  

This project is perfect for learning how **OpenMUC**, **BACnet**, and **OSGi** components work together to create scalable IoT/industrial automation solutions.  

---

##  Features

- **Server & Client Simulation**:  
  - Server channels generate **random Float and Boolean values** every 2 seconds.  
  - Client channels automatically receive updates from the server.  

- **Real-Time Data Propagation**:  
  - Server channel values are immediately mirrored on client channels for monitoring or processing.  

- **Fully OSGi-Ready**:  
  - Built using OSGi annotations for dynamic module activation and deactivation.  

- **Beginner-Friendly**:  
  - Simple, clear code structure to understand BACnet device communication in Java.

---

##  How It Works

1. **Server Device** generates random values on two channels:  
   - `ServerFloat` → analog value (0–100)  
   - `ServerBool` → binary value (true/false)  

2. **Client Device** listens to server channels and automatically receives updates.

3. **Scheduler** updates server channels every 2 seconds, while mirroring values on client channels.

4. Logs provide real-time updates for both server and client channel values.

---

❤️ About

This project is made for tech enthusiasts who love exploring industrial protocols, IoT automation, and OSGi modular applications.

Learn, experiment, and get inspired!

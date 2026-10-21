# Run SMART Power-Track on Windows

The prebuilt app in `app/smart-power-track.jar` needs Java 17 or newer. The development build was tested with Java 25.0.3. Maven and a separate database server are not required to run this supplied app.

## 1. Install Java

Download an Eclipse Temurin JDK from https://adoptium.net/temurin/releases/. Choose JDK 25, Windows, and the architecture matching your computer (usually x64 on Intel/AMD). For x64 Windows, use the MSI installer. Keep **Add to PATH** selected and enable **Set JAVA_HOME** if available.

Open a new Command Prompt and run:

```bat
java -version
```

Confirm the major version is 17 or newer. If Java is not recognized, reopen the installer and enable its PATH option, then open another Command Prompt. If an old version still appears, `where java` shows which installation Windows is finding.

Official installer instructions: https://adoptium.net/installation/windows/.

## 2. Download and extract the repository

On GitHub, choose **Code > Download ZIP**, then right-click the downloaded ZIP in File Explorer and choose **Extract All**. Alternatively, clone the repository with Git.

Open the extracted folder containing `start.cmd`, `README.md`, `src`, and the `app` directory. Keep the full directory together in a writable location such as Documents. Do not run the launcher directly inside the ZIP.

## 3. Start the application

Double-click `start.cmd`. Leave its command window open and wait for a message containing `Started PowerTrackApplication`.

The initial launch creates the local H2 database and prepares sample appliances and history.

## 4. Open the dashboard

Open http://127.0.0.1:8080 in a browser. Click **Open the demo dashboard**, or sign in with:

- Email: `demo@powertrack.local`
- Password: `PowerTrack123!`

The sample household includes 21 days of simulated history. Newly registered accounts start empty and can load sample data.

## 5. Stop and restart

Press **Ctrl+C** in the application's command window. If Windows asks `Terminate batch job (Y/N)?`, enter `Y`. Run `start.cmd` again to restart.

The `data` directory stores this computer's database and uploaded images. Retain it to keep your records. Stop the app before backing up that directory.

## Troubleshooting

| Symptom | Action |
|---|---|
| Java not recognized | Open a new Command Prompt after installation; check the Java installer PATH option. |
| Unsupported class version | Install a current Java version and check `java -version` and `where java`. |
| Unable to access the JAR | Extract the whole ZIP and check that `app\smart-power-track.jar` exists. |
| Browser cannot connect | Keep the application window open, wait for successful startup, and use `http://127.0.0.1:8080`. |
| Database locked | Stop the other running Power-Track instance, then start once. |
| Port 8080 in use | Stop the conflicting app or use the alternative command below. |

For another port, open the project directory in File Explorer, type `cmd` in its address bar, press Enter, then run:

```bat
java -jar "app\smart-power-track.jar" --server.port=8081
```

Open http://127.0.0.1:8081 instead.

Each group member runs a separate local instance and database. This does not publish a shared website. Sensor data are simulated, costs are estimates, and image review uses manual entry or labelled sample fixtures rather than a connected live AI service.

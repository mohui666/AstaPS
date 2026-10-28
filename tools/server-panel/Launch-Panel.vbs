Option Explicit
Dim shell, files, folder, script, command
Set shell = CreateObject("WScript.Shell")
Set files = CreateObject("Scripting.FileSystemObject")
folder = files.GetParentFolderName(WScript.ScriptFullName)
script = files.BuildPath(folder, "Start-Panel.ps1")
If Not files.FileExists(script) Then
  MsgBox "Start-Panel.ps1 is missing. Reinstall the MGC server panel.", 16, "MGC"
  WScript.Quit 1
End If
command = "powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -File """ & script & """"
shell.Run command, 0, False

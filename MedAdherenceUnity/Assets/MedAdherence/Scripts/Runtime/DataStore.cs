using System;
using System.IO;
using MedAdherence.Core;
using UnityEngine;

namespace MedAdherence
{
    /// <summary>Loads and saves the regimen, dose log and settings as JSON in the app's private storage.</summary>
    public class DataStore
    {
        public AppData Data { get; private set; }
        public event Action Changed;

        static string FilePath => Path.Combine(Application.persistentDataPath, "medadherence.json");
        public static string EvidenceDir => Path.Combine(Application.persistentDataPath, "evidence");
        public static string ExportDir => Path.Combine(Application.persistentDataPath, "exports");

        public void Load()
        {
            try
            {
                if (File.Exists(FilePath)) Data = JsonUtility.FromJson<AppData>(File.ReadAllText(FilePath));
            }
            catch (Exception e)
            {
                Debug.LogError("[MedAdherence] Could not read data file, keeping a backup: " + e.Message);
                try { File.Copy(FilePath, FilePath + ".corrupt-" + DateTime.Now.Ticks, true); } catch { }
            }
            if (Data == null) Data = new AppData();
            if (Data.settings == null) Data.settings = new AppSettings();
        }

        public void Save()
        {
            string tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, JsonUtility.ToJson(Data, true));
            if (File.Exists(FilePath)) File.Delete(FilePath);
            File.Move(tmp, FilePath);
            Changed?.Invoke();
        }

        public void Upsert(Medication med)
        {
            int i = Data.medications.FindIndex(m => m.id == med.id);
            if (i >= 0) Data.medications[i] = med; else Data.medications.Add(med);
            Save();
        }

        public void Remove(string medId)
        {
            Data.medications.RemoveAll(m => m.id == medId);
            Save();
        }

        public string WriteExport(string fileName, string content)
        {
            Directory.CreateDirectory(ExportDir);
            string path = Path.Combine(ExportDir, fileName);
            File.WriteAllText(path, content);
            return path;
        }
    }
}

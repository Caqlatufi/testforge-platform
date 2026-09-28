package config

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const DefaultProtocol = "2026-07-28"

type BuildInfo struct {
	Version   string `json:"version"`
	Commit    string `json:"commit"`
	BuildDate string `json:"buildDate"`
	Protocol  string `json:"protocol"`
}

type Config struct {
	BaseURL     string
	Token       string
	Workspace   string
	BindingFile string
	Timeout     time.Duration
	Build       BuildInfo
}

func Load(args []string, build BuildInfo) (Config, error) {
	userConfig, err := os.UserConfigDir()
	if err != nil {
		return Config{}, fmt.Errorf("resolve user config directory: %w", err)
	}
	defaults := Config{
		BaseURL:     env("TESTFORGE_URL", "http://127.0.0.1:8081"),
		Token:       strings.TrimSpace(os.Getenv("TESTFORGE_TOKEN")),
		Workspace:   env("TESTFORGE_WORKSPACE", "."),
		BindingFile: env("TESTFORGE_BINDINGS", filepath.Join(userConfig, "testforge-mcp", "bindings.json")),
		Timeout:     30 * time.Second,
		Build:       build,
	}
	if value := strings.TrimSpace(os.Getenv("TESTFORGE_TIMEOUT")); value != "" {
		defaults.Timeout, err = parseDuration(value)
		if err != nil {
			return Config{}, fmt.Errorf("TESTFORGE_TIMEOUT: %w", err)
		}
	}

	fs := flag.NewFlagSet("testforge-mcp", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	fs.StringVar(&defaults.BaseURL, "url", defaults.BaseURL, "TestForge base URL")
	fs.StringVar(&defaults.Workspace, "workspace", defaults.Workspace, "authorized workspace root")
	fs.StringVar(&defaults.BindingFile, "bindings", defaults.BindingFile, "local workspace binding file")
	timeout := defaults.Timeout.String()
	fs.StringVar(&timeout, "timeout", timeout, "HTTP timeout, for example 30s")
	if err := fs.Parse(args); err != nil {
		return Config{}, err
	}
	if fs.NArg() != 0 {
		return Config{}, fmt.Errorf("unexpected arguments: %s", strings.Join(fs.Args(), " "))
	}
	defaults.Timeout, err = parseDuration(timeout)
	if err != nil {
		return Config{}, fmt.Errorf("timeout: %w", err)
	}
	defaults.BaseURL = strings.TrimRight(strings.TrimSpace(defaults.BaseURL), "/")
	parsed, err := url.Parse(defaults.BaseURL)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return Config{}, errors.New("TestForge URL must be an absolute http or https URL")
	}
	if defaults.Timeout <= 0 {
		return Config{}, errors.New("timeout must be greater than zero")
	}
	if strings.TrimSpace(defaults.Workspace) == "" {
		return Config{}, errors.New("workspace must not be empty")
	}
	if strings.TrimSpace(defaults.BindingFile) == "" {
		return Config{}, errors.New("bindings path must not be empty")
	}
	return defaults, nil
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func parseDuration(value string) (time.Duration, error) {
	if seconds, err := strconv.ParseFloat(value, 64); err == nil {
		if seconds <= 0 {
			return 0, errors.New("must be greater than zero")
		}
		return time.Duration(seconds * float64(time.Second)), nil
	}
	duration, err := time.ParseDuration(value)
	if err != nil || duration <= 0 {
		return 0, errors.New("must be a positive duration or number of seconds")
	}
	return duration, nil
}

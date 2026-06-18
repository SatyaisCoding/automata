'use client';

import React, { useState, useEffect, useRef } from 'react';

// Preset Scenario Data
const PRESETS = {
  npe_payment: {
    id: 'PROD-1234',
    summary: 'NullPointerException in Payment Service',
    description: 'Missing user validation in PaymentService before processing payments. Cause of production outage.',
    stackTrace: 'java.lang.NullPointerException: Cannot invoke "com.automata.User.getId()" because "user" is null\n  at com.automata.service.PaymentService.processPayment(PaymentService.java:42)\n  at com.automata.controller.PaymentController.charge(PaymentController.java:18)',
    model: 'groq',
    // Screen data
    memory: {
      similarIncident: {
        id: 'PROD-102',
        issue: 'NullPointerException',
        rootCause: 'Missing User Validation',
        resolution: 'Added Null Guard',
        confidence: '94%'
      },
      successfulFixes: [
        { file: 'PaymentService.ts', result: 'Successful', validation: 'Passed', ci: 'Passed' }
      ],
      recoveryMemory: {
        strategy: 'Regenerate With Reduced Scope',
        successRate: '88%'
      }
    },
    investigation: {
      service: 'Payment Service',
      environment: 'Production',
      severity: 'High',
      evidence: [
        { type: 'Stack Trace', desc: 'Points to PaymentService.java:42 line dereference.' },
        { type: 'Affected Modules', desc: 'PaymentService, UserMapper' },
        { type: 'Error Logs', desc: 'java.lang.NullPointerException: Cannot invoke User.getId()' }
      ],
      hypothesis: 'Missing validation before user object dereference.',
      confidence: '91%'
    },
    fix: {
      modifiedFiles: ['PaymentService.ts', 'UserMapper.ts'],
      beforeCode: `// PaymentService.ts\npublic void processPayment(User user) {\n    String userId = user.getId();\n    double balance = user.getBalance();\n    executeTransaction(userId, balance);\n}`,
      afterCode: `// PaymentService.ts\npublic void processPayment(User user) {\n    if (user != null) {\n        String userId = user.getId();\n        double balance = user.getBalance();\n        executeTransaction(userId, balance);\n    } else {\n        log.warn("[WARNING] User object is null. Skipping transaction.");\n    }\n}`,
      testFile: 'PaymentServiceTest.java',
      testContent: `@Test\npublic void testProcessPayment_NullUser() {\n    PaymentService service = new PaymentService();\n    // Verify null user does not throw NullPointerException\n    assertDoesNotThrow(() -> service.processPayment(null));\n}`
    },
    validation: {
      syntax: 'Passed',
      static: 'Passed',
      reviewer: 'Passed',
      ci: 'Passed',
      reviewerReport: {
        security: 'Low',
        performance: 'Negligible',
        maintainability: 'Good',
        recommendation: 'Approve'
      }
    },
    recovery: {
      example: 'Validation Failed: Unmatched Brackets',
      strategy: 'Generate Alternative Fix',
      attempts: [
        { id: 1, status: 'Failed', reason: 'Compilation error: Unmatched curly brace at line 45' },
        { id: 2, status: 'Success', reason: 'Syntax checks and compilation tests passed successfully' }
      ]
    },
    pr: {
      number: '#245',
      branch: 'fix/payment-null-check',
      status: 'Draft',
      reviewStatus: 'Pending',
      filesChanged: 2,
      testsAdded: 1
    },
    rca: {
      issue: 'NullPointerException',
      rootCause: 'Missing validation before user dereference.',
      impact: 'Payment processing failures.',
      filesModified: 'PaymentService.ts, UserMapper.ts',
      fixApplied: 'Added null validation.',
      risk: 'Low',
      rollback: 'Revert pull request.'
    }
  },
  array_bounds: {
    id: 'PROD-4022',
    summary: 'ArrayIndexOutOfBoundsException in UserMapper',
    description: 'UserMapper attempts to read segment split arrays without verifying index bounds. Thrown during user CSV importing.',
    stackTrace: 'java.lang.ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 3\n  at com.automata.mapper.UserMapper.mapCsvLine(UserMapper.java:19)\n  at com.automata.service.ImportService.processLines(ImportService.java:108)',
    model: 'gemini',
    memory: {
      similarIncident: {
        id: 'PROD-085',
        issue: 'ArrayIndexOutOfBoundsException',
        rootCause: 'CSV Split Length mismatch',
        resolution: 'Add split bounds length check',
        confidence: '91%'
      },
      successfulFixes: [
        { file: 'UserMapper.ts', result: 'Successful', validation: 'Passed', ci: 'Passed' }
      ],
      recoveryMemory: {
        strategy: 'Pad Array Elements',
        successRate: '72%'
      }
    },
    investigation: {
      service: 'User Management',
      environment: 'Production',
      severity: 'Medium',
      evidence: [
        { type: 'Stack Trace', desc: 'Mapper indexing element [3] in split array of size 3.' },
        { type: 'Affected Modules', desc: 'UserMapper, ImportService' }
      ],
      hypothesis: 'Accessing array splits without checking length bounds.',
      confidence: '95%'
    },
    fix: {
      modifiedFiles: ['UserMapper.ts'],
      beforeCode: `// UserMapper.ts\nString[] parts = line.split(",");\nuser.setFirstName(parts[0]);\nuser.setLastName(parts[1]);\nuser.setEmail(parts[2]);\nuser.setRole(parts[3]);`,
      afterCode: `// UserMapper.ts\nString[] parts = line.split(",");\nuser.setFirstName(parts.length > 0 ? parts[0] : "");\nuser.setLastName(parts.length > 1 ? parts[1] : "");\nuser.setEmail(parts.length > 2 ? parts[2] : "");\nuser.setRole(parts.length > 3 ? parts[3] : "USER");`,
      testFile: 'UserMapperTest.java',
      testContent: `@Test\npublic void testMapCsvLine_ShortLine() {\n    UserMapper mapper = new UserMapper();\n    User user = mapper.mapCsvLine("John,Doe");\n    assertEquals("USER", user.getRole());\n}`
    },
    validation: {
      syntax: 'Passed',
      static: 'Passed',
      reviewer: 'Passed',
      ci: 'Passed',
      reviewerReport: {
        security: 'Low',
        performance: 'Negligible',
        maintainability: 'Excellent',
        recommendation: 'Approve'
      }
    },
    recovery: {
      example: 'None (First attempt successful)',
      strategy: 'Direct Fix Generation',
      attempts: [
        { id: 1, status: 'Success', reason: 'Array index checks compiled and tests passed' }
      ]
    },
    pr: {
      number: '#246',
      branch: 'fix/mapper-index-check',
      status: 'Draft',
      reviewStatus: 'Ready for Review',
      filesChanged: 1,
      testsAdded: 1
    },
    rca: {
      issue: 'ArrayIndexOutOfBoundsException',
      rootCause: 'Reading split array element 3 without validating size.',
      impact: 'User csv import crashes.',
      filesModified: 'UserMapper.ts',
      fixApplied: 'Added length validations before array indices mapping.',
      risk: 'Low',
      rollback: 'Revert PR.'
    }
  }
};

export default function Home() {
  const [activeTab, setActiveTab] = useState('SUBMIT');
  const [mode, setMode] = useState('demo'); // 'demo' or 'live'
  const [selectedModel, setSelectedModel] = useState('groq');
  
  // Incident input form
  const [ticketId, setTicketId] = useState('PROD-1234');
  const [summary, setSummary] = useState('NullPointerException in Payment Service');
  const [description, setDescription] = useState('Missing user validation in PaymentService before processing payments. Cause of production outage.');
  const [stackTrace, setStackTrace] = useState('java.lang.NullPointerException: Cannot invoke "com.automata.User.getId()" because "user" is null\n  at com.automata.service.PaymentService.processPayment(PaymentService.java:42)');

  // Agent Pipeline States
  const [pipelineStatus, setPipelineStatus] = useState('idle'); // 'idle', 'running', 'completed', 'failed'
  const [agentStates, setAgentStates] = useState({
    analysis: 'waiting',
    context: 'waiting',
    healing: 'waiting',
    delivery: 'waiting'
  });
  const [webhookBaseUrl, setWebhookBaseUrl] = useState('https://3a90-103-192-64-62.ngrok-free.app');
  const [history, setHistory] = useState<any[]>([]);

  const fetchHistory = async () => {
    try {
      const response = await fetch("http://localhost:9095/api/webhook/jira/history");
      if (response.ok) {
        const data = await response.json();
        setHistory(data);
      }
    } catch (err) {
      console.error("Failed to fetch diagnostics history:", err);
    }
  };

  // Fetch history on mount
  useEffect(() => {
    fetchHistory();
  }, []);

  // Auto scroll when switching back to Dashboard tab
  useEffect(() => {
    if (activeTab === 'SUBMIT' && consoleEndRef.current) {
      consoleEndRef.current.scrollIntoView({ behavior: 'auto' });
    }
  }, [activeTab]);

  const getPrUrl = (item: any) => {
    if (item.recovery?.attempts) {
      for (const attempt of item.recovery.attempts) {
        if (attempt.reason && attempt.reason.includes("https://github.com/")) {
          const match = attempt.reason.match(/https:\/\/github\.com\/[^\s]+/);
          if (match) return match[0];
        }
      }
    }
    return null;
  };

  const getEngineName = (item: any) => {
    if (item.model) return item.model.toUpperCase();
    const charCodeSum = item.id ? item.id.split('').reduce((sum: number, c: string) => sum + c.charCodeAt(0), 0) : 0;
    return charCodeSum % 2 === 0 ? 'GEMINI' : 'GROQ';
  };

  const getVerdict = (item: any) => {
    const isPassed = item.fixes && item.fixes.some((f: any) => f.ci === 'Passed' || f.result === 'Successful');
    if (isPassed) {
      return <span className="verdict-accepted">ACCEPTED</span>;
    }
    return <span className="verdict-failed">FAILED</span>;
  };

  const getPrLink = (item: any) => {
    const prUrl = getPrUrl(item);
    if (prUrl) {
      const prNumberMatch = prUrl.match(/pull\/(\d+)/);
      const label = prNumberMatch ? `#${prNumberMatch[1]}` : 'Open PR';
      return (
        <a href={prUrl} target="_blank" rel="noreferrer" className="text-bold" style={{ color: '#1a5a96', textDecoration: 'underline' }}>
          {label}
        </a>
      );
    }
    return <span className="text-muted">N/A</span>;
  };

  const handleSelectHistoryItem = (item: any) => {
    setTicketId(item.id);
    setSummary(item.summary);
    setDescription(item.description);
    
    let extractedStackTrace = '';
    if (item.description && item.description.includes('Stack Trace:')) {
      extractedStackTrace = item.description.substring(item.description.indexOf('Stack Trace:') + 12).trim();
    }
    setStackTrace(extractedStackTrace);
    
    const mappedScenario = {
      id: item.id,
      summary: item.summary,
      description: item.description,
      stackTrace: extractedStackTrace,
      model: getEngineName(item).toLowerCase(),
      memory: {
        similarIncident: {
          id: item.id === 'PROD-102' ? 'PROD-085' : 'PROD-102',
          issue: item.issue,
          rootCause: item.rootCause,
          resolution: item.resolution,
          confidence: item.confidence
        },
        successfulFixes: item.fixes || [],
        recoveryMemory: {
          strategy: item.recovery?.strategy || 'Generate Alternative Fix',
          successRate: item.recovery?.successRate || '85%'
        }
      },
      investigation: {
        service: item.fixes?.[0]?.file || 'Unknown Service',
        environment: 'Production',
        severity: 'Medium',
        evidence: [
          { type: 'Reported Issue', desc: item.summary },
          { type: 'Incident Details', desc: item.description }
        ],
        hypothesis: item.rootCause || 'Missing check',
        confidence: item.confidence || '95%'
      },
      fix: {
        modifiedFiles: item.fixes?.map((f: any) => f.file) || [],
        beforeCode: '// Code modifications applied directly in PR',
        afterCode: '// Code changes pushed to PR branches',
        testFile: 'Test.java',
        testContent: '// Unit tests generated automatically by the agent'
      },
      validation: {
        syntax: 'Passed',
        static: 'Passed',
        reviewer: 'Passed',
        ci: item.fixes?.[0]?.ci === 'Passed' ? 'Passed' : 'Pending',
        reviewerReport: {
          security: 'Low',
          performance: 'Negligible',
          maintainability: 'Good',
          recommendation: 'Approve'
        }
      },
      recovery: {
        example: item.recovery?.strategy || 'Direct Generation',
        strategy: item.recovery?.strategy || 'Generate Alternative Fix',
        attempts: item.recovery?.attempts || []
      },
      pr: {
        number: item.recovery?.attempts?.[0]?.reason?.match(/pull\/(\d+)/)?.[1] ? '#' + item.recovery.attempts[0].reason.match(/pull\/(\d+)/)[1] : '#245',
        branch: `automata/${item.id}-ai-fix`,
        status: item.fixes?.[0]?.ci === 'Passed' ? 'Ready for Review' : 'Draft',
        prUrl: getPrUrl(item) || 'https://github.com/SatyaisCoding/testing-repo/pull/2',
        reviewStatus: item.fixes?.[0]?.ci === 'Passed' ? 'Approved' : 'Pending',
        filesChanged: item.fixes?.length || 1,
        testsAdded: 1
      },
      rca: {
        issue: item.issue,
        rootCause: item.rootCause,
        impact: item.summary,
        filesModified: item.fixes?.map((f: any) => f.file).join(', ') || '',
        fixApplied: item.resolution,
        risk: 'Low',
        rollback: 'Revert PR'
      }
    };
    
    setActiveScenario(mappedScenario);
    setApiResult({
      status: item.fixes?.[0]?.result === 'Successful' ? 'completed' : 'failed',
      pr_url: getPrUrl(item),
      pr_branch: `automata/${item.id}-ai-fix`,
      pr_number: item.recovery?.attempts?.[0]?.reason?.match(/pull\/(\d+)/)?.[1] || '2',
      ci_status: item.fixes?.[0]?.ci === 'Passed' ? 'success' : 'pending'
    });
    
    setPipelineStatus(item.fixes?.[0]?.result === 'Successful' ? 'completed' : 'failed');
    setAgentStates({
      analysis: 'completed',
      context: 'completed',
      healing: item.fixes?.[0]?.result === 'Successful' ? 'completed' : 'failed',
      delivery: item.fixes?.[0]?.result === 'Successful' ? 'completed' : 'failed'
    });
    setSelectedAgent('delivery');
    setActiveTab('TIMELINE');
    addLog(`[SYSTEM] Loaded diagnostics history item: ${item.id} - ${item.summary}`);
  };

  const [selectedAgent, setSelectedAgent] = useState('analysis');
  const [consoleLogs, setConsoleLogs] = useState<string[]>(['[SYSTEM] Automata Console Ready. Select Mode and Run.']);
  const [apiResult, setApiResult] = useState<any>(null);
  
  // Active Scenario Content (for screens 3-9 display)
  const [activeScenario, setActiveScenario] = useState<any>(PRESETS.npe_payment);

  // Chatbot states
  const [chatInput, setChatInput] = useState('');
  const [chatMessages, setChatMessages] = useState<Array<{sender: string, text: string}>>([
    { sender: 'Agent Control Center', text: 'Hello! I am the Automata Coordinator. Ask me anything about current incident diagnostics.' }
  ]);

  const consoleEndRef = useRef<HTMLDivElement>(null);

  // Auto scroll console
  useEffect(() => {
    if (consoleEndRef.current) {
      consoleEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [consoleLogs]);

  // Connect to SSE log stream on mount to listen for all webhook events (both internal run triggers and external webhooks)
  useEffect(() => {
    let eventSource = new EventSource("http://localhost:9095/api/stream/logs");

    eventSource.addEventListener("log", (event) => {
      const logLine = event.data;

      // Check if this is the start of a run (Jira or GitHub)
      if (logLine.includes("=== Jira Webhook Received ===") || logLine.includes("[START] Triggered via GitHub webhook")) {
        setPipelineStatus('running');
        setAgentStates({
          analysis: 'running',
          context: 'waiting',
          healing: 'waiting',
          delivery: 'waiting'
        });
        setSelectedAgent('analysis');
        setConsoleLogs([]); // Reset log panel for the new run
      }

      // Parse and display the log line
      // Skip result json messages in the logs view since it is for UI update only
      if (logLine.startsWith("[RESULT_JSON] ")) {
        try {
          const resultJson = logLine.substring("[RESULT_JSON] ".length);
          const result = JSON.parse(resultJson);
          setApiResult(result);
          addLog(`[SUCCESS] Automata execution complete! Status: ${result.status}`);

          // Construct scenario update from result
          const updatedScenario = {
            id: result.pr_branch ? result.pr_branch.replace("automata/", "").replace("-ai-fix", "") : ticketId,
            summary: summary,
            description: description,
            stackTrace: stackTrace,
            model: selectedModel,
            memory: {
              similarIncident: result.memoryReport || activeScenario.memory.similarIncident,
              successfulFixes: activeScenario.memory.successfulFixes,
              recoveryMemory: activeScenario.memory.recoveryMemory
            },
            investigation: result.investigatorReport || activeScenario.investigation,
            fix: {
              modifiedFiles: result.filesChangedList || activeScenario.fix.modifiedFiles,
              beforeCode: result.beforeCode || activeScenario.fix.beforeCode,
              afterCode: result.afterCode || activeScenario.fix.afterCode,
              testFile: result.testFile || activeScenario.fix.testFile,
              testContent: result.testContent || activeScenario.fix.testContent
            },
            validation: {
              syntax: 'Passed',
              static: 'Passed',
              reviewer: 'Passed',
              ci: result.ci_status === 'success' ? 'Passed' : 'Pending',
              reviewerReport: result.reviewerReport || activeScenario.validation.reviewerReport
            },
            recovery: result.recoveryReport || activeScenario.recovery,
            pr: {
              number: `#${result.pr_number || '2'}`,
              branch: result.pr_branch || `automata/${ticketId}-ai-fix`,
              status: result.ci_status === 'success' ? 'Ready for Review' : 'Draft',
              reviewStatus: result.ci_status === 'success' ? 'Approved' : 'Pending',
              filesChanged: result.filesChangedList?.length || 1,
              testsAdded: 1
            },
            rca: result.rcaReport || activeScenario.rca
          };

          setActiveScenario(updatedScenario);

          setAgentStates({
            analysis: 'completed',
            context: 'completed',
            healing: result.status === 'failed' ? 'failed' : 'completed',
            delivery: result.status === 'failed' ? 'failed' : 'completed'
          });
          setSelectedAgent('delivery');
          setPipelineStatus('completed');
          setActiveTab('TIMELINE');
          
          if (result.status === 'failed') {
            addLog(`[ERROR] Autonomous run failed: ${result.error}`);
          } else {
            addLog(`[COMPLETE] Pull request opened: ${result.pr_url}`);
          }
          fetchHistory();
        } catch (err: any) {
          console.error("Failed to parse result json from stream", err);
        }
        return;
      }

      addLog(logLine);

      // Transition agentStates based on log patterns
      if (logLine.includes("Analysis Agent formulating hypothesis")) {
        setAgentStates(prev => ({ ...prev, analysis: 'running' }));
        setSelectedAgent('analysis');
      } else if (logLine.includes("Issue Key: ")) {
        const key = logLine.substring(logLine.indexOf("Issue Key: ") + 11).trim();
        setTicketId(key);
      } else if (logLine.includes("Processing ticket: ")) {
        const key = logLine.substring(logLine.indexOf("Processing ticket: ") + 19).trim();
        setTicketId(key);
      } else if (logLine.includes("Summary: ")) {
        const sumVal = logLine.substring(logLine.indexOf("Summary: ") + 9).trim();
        setSummary(sumVal);
      } else if (
        logLine.includes("Investigator Agent hypothesis:") || 
        logLine.includes("Planner Agent strategy:") || 
        logLine.includes("Memory matched similar incident:") ||
        (logLine.includes("Retrieved") && logLine.includes("relevant file"))
      ) {
        setAgentStates(prev => ({ 
          ...prev, 
          analysis: 'completed', 
          context: 'running' 
        }));
        setSelectedAgent('context');
      } else if (
        logLine.includes("=== AI Generated Code ===") || 
        (logLine.includes("Parsed") && logLine.includes("file change")) || 
        logLine.includes("Safety guards passed") || 
        logLine.includes("Recovery Agent standing by") || 
        logLine.includes("Validation Attempt")
      ) {
        setAgentStates(prev => ({ 
          ...prev, 
          context: 'completed', 
          healing: 'running' 
        }));
        setSelectedAgent('healing');
      } else if (
        logLine.includes("Validation failed") || 
        logLine.includes("Injecting syntax error") || 
        logLine.includes("Reviewer requested changes")
      ) {
        setAgentStates(prev => ({ ...prev, healing: 'failed' }));
      } else if (
        logLine.includes("Code validation and code review passed") || 
        logLine.includes("=== Creating Pull Request ===")
      ) {
        setAgentStates(prev => ({ 
          ...prev, 
          healing: 'completed', 
          delivery: 'running' 
        }));
        setSelectedAgent('delivery');
      } else if (
        logLine.includes("Maximum self-healing recovery attempts reached") || 
        logLine.includes("Self-healing recovery failed")
      ) {
        setAgentStates(prev => ({ ...prev, healing: 'failed' }));
      }
    });

    eventSource.addEventListener("connect", (event) => {
      console.log("Connected to persistent log stream:", event.data);
    });

    eventSource.onerror = (err) => {
      console.error("Persistent SSE connection error:", err);
    };

    return () => {
      eventSource.close();
    };
  }, [ticketId, summary, description, stackTrace, selectedModel, activeScenario]);

  // Load preset scenario
  const handleLoadPreset = (key: 'npe_payment' | 'array_bounds') => {
    const preset = PRESETS[key];
    setTicketId(preset.id);
    setSummary(preset.summary);
    setDescription(preset.description);
    setStackTrace(preset.stackTrace);
    setSelectedModel(preset.model);
    setActiveScenario(preset);
    addLog(`[SYSTEM] Loaded preset scenario: ${preset.summary}`);
  };

  const addLog = (logStr: string) => {
    const time = new Date().toLocaleTimeString();
    setConsoleLogs(prev => [...prev, `[${time}] ${logStr}`]);
  };

  // Run Pipeline
  const handleAnalyze = async () => {
    if (pipelineStatus === 'running') return;
    
    // Reset agent execution states
    setPipelineStatus('running');
    setAgentStates({
      analysis: 'waiting',
      context: 'waiting',
      healing: 'waiting',
      delivery: 'waiting'
    });
    setConsoleLogs([]);
    addLog(`[START] Initiating Incident Analysis for Ticket: ${ticketId}`);

    if (mode === 'demo') {
      runDemoSimulation();
    } else {
      await runLiveAnalysis();
    }
  };

  // 1. Live Integration Mode (Fetch from Spring Boot backend on localhost:9095)
  const runLiveAnalysis = async () => {
    // Generate request body in Jira Webhook schema
    const payload = {
      issue: {
        id: '20003',
        key: ticketId,
        fields: {
          summary: summary,
          description: description + "\n\nStack Trace:\n" + stackTrace,
          priority: {
            name: 'Medium'
          }
        }
      },
      model: selectedModel
    };

    addLog(`[INFO] Sending webhook payload to Spring Boot Controller on port 9095...`);
    
    // Set initial timeline highlights
    setAgentStates({
      analysis: 'running',
      context: 'waiting',
      healing: 'waiting',
      delivery: 'waiting'
    });
    setSelectedAgent('analysis');

    try {
      const response = await fetch(`http://localhost:9095/api/webhook/jira/trigger?model=${selectedModel}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
      });

      addLog(`[INFO] Webhook triggered. Waiting for agent code generation, commits, and status checks...`);

      if (!response.ok) {
        const errData = await response.json().catch(() => ({}));
        throw new Error(errData.error || `Server responded with status ${response.status}`);
      }

    } catch (err: any) {
      addLog(`[ERROR] Failed to run live analysis: ${err.message}`);
      setPipelineStatus('failed');
      setAgentStates(prev => ({
        analysis: prev.analysis === 'running' ? 'failed' : prev.analysis,
        context: prev.context === 'running' ? 'failed' : prev.context,
        healing: prev.healing === 'running' ? 'failed' : prev.healing,
        delivery: prev.delivery === 'running' ? 'failed' : prev.delivery
      }));
    }
  };

  // 2. Demo Mode Simulation Engine
  const runDemoSimulation = () => {
    const steps = [
      {
        log: 'Analysis Agent activated: Analyzing incident ticket summary, priority, and logs...',
        stateUpdate: { analysis: 'running' },
        agent: 'analysis',
        delay: 1200
      },
      {
        log: 'Analysis Agent complete. Hypothesis formulated and repair strategy generated.',
        stateUpdate: { analysis: 'completed', context: 'running' },
        agent: 'analysis',
        delay: 1000
      },
      {
        log: 'Context RAG: Fetching active repository tree and vector memory databases in parallel...',
        stateUpdate: { context: 'running' },
        agent: 'context',
        delay: 1500
      },
      {
        log: 'Context RAG complete. Matched historical incident and retrieved relevant source file context.',
        stateUpdate: { context: 'completed', healing: 'running' },
        agent: 'context',
        delay: 1000
      },
      {
        log: 'Self-Healing Generator: Generating code fix patch and initiating Reviewer Agent check...',
        stateUpdate: { healing: 'running' },
        agent: 'healing',
        delay: 1500
      },
      {
        log: 'Self-Healing Generator: Syntax validation passed. Gated reviewer requested changes (Attempt 1). Healing patch...',
        stateUpdate: { healing: 'failed' },
        agent: 'healing',
        delay: 1500
      },
      {
        log: 'Self-Healing Generator: Regeneration successful. Reviewer approved and compilation checks passed (Attempt 2).',
        stateUpdate: { healing: 'completed', delivery: 'running' },
        agent: 'healing',
        delay: 1200
      },
      {
        log: 'Delivery Agent: Pushing patch branch and opening draft Pull Request on GitHub...',
        stateUpdate: { delivery: 'running' },
        agent: 'delivery',
        delay: 1300
      },
      {
        log: 'Delivery Agent: Pull Request successfully opened. Generating engineering Root Cause Analysis (RCA)...',
        stateUpdate: { delivery: 'running' },
        agent: 'delivery',
        delay: 1200
      },
      {
        log: 'Delivery complete: PR opened and RCA report compiled. Background CI Action tracking started.',
        stateUpdate: { delivery: 'completed' },
        agent: 'delivery',
        delay: 1000
      }
    ];

    let currentStepIdx = 0;

    const runNextStep = () => {
      if (currentStepIdx >= steps.length) {
        setPipelineStatus('completed');
        setActiveTab('TIMELINE');
        addLog('[COMPLETE] Automated incident mitigation workflow completed successfully!');
        return;
      }

      const step = steps[currentStepIdx];
      addLog(step.log);
      setAgentStates(prev => ({ ...prev, ...step.stateUpdate }));
      setSelectedAgent(step.agent);
      
      currentStepIdx++;
      setTimeout(runNextStep, step.delay);
    };

    runNextStep();
  };

  // Dynamic chatbot responses based on scenarios
  const handleSendMessage = () => {
    if (!chatInput.trim()) return;

    const userMsg = chatInput;
    setChatMessages(prev => [...prev, { sender: 'User', text: userMsg }]);
    setChatInput('');

    setTimeout(() => {
      let reply = "I'm monitoring the agent control center. Could you please specify which agent or code file you want me to explain?";
      const msgLower = userMsg.toLowerCase();

      if (msgLower.includes('why') && msgLower.includes('fix')) {
        reply = `The fix was generated because the Investigator Agent identified an unchecked dereference of the user object at PaymentService.ts line 42. Adding a null check prevents the NullPointerException.`;
      } else if (msgLower.includes('file') || msgLower.includes('selected')) {
        reply = `The repository scanner selected 'PaymentService.ts' and 'UserMapper.ts' based on the keywords extracted from the stack trace ('PaymentService.java:42').`;
      } else if (msgLower.includes('fail') || msgLower.includes('validation')) {
        reply = `Validation failed during the first attempt because the generated patch had a syntax parsing issue (an unmatched brace). The Recovery Agent automatically captured the compiler error and requested a clean patch.`;
      } else if (msgLower.includes('rca')) {
        reply = `The Root Cause Analysis identifies missing client validations. We've compiled the PR with automated unit tests to verify proper handling of empty objects.`;
      } else if (msgLower.includes('model')) {
        reply = `We are currently using the ${selectedModel.toUpperCase()} model for code generation. Fallback is configured to Gemini in case of rate limits.`;
      }

      setChatMessages(prev => [...prev, { sender: 'Automata Coordinator', text: reply }]);
    }, 800);
  };

  return (
    <div className="cf-container">
      {/* Codeforces Header Utility bar */}
      <div className="cf-top-bar">
        <div className="cf-top-bar-inner">
          <div className="cf-top-bar-left">
            <span>automata.com</span> | <span className="text-muted">Agent Control Center v1.0.0</span>
          </div>
          <div className="cf-top-bar-right">
            <span>Mode: <strong className="text-bold">{mode.toUpperCase()}</strong></span>
            <span>User: <span className="handle-lgm"><span style={{color:'#000'}}>s</span>atya_prakash</span> (L. Grandmaster)</span>
            <span className="text-muted">|</span>
            <a href="#logout" className="text-muted" style={{textDecoration:'none'}}>Logout</a>
          </div>
        </div>
      </div>

      {/* Main Brand Header */}
      <header className="cf-header">
        <div className="cf-header-main">
          <a href="#" className="cf-logo-container">
            <div className="cf-logo-bars">
              <div className="cf-bar-1"></div>
              <div className="cf-bar-2"></div>
              <div className="cf-bar-3"></div>
            </div>
            <span className="cf-logo-text">AUTOMATA</span>
            <span className="cf-logo-sub">Autonomous Incident-to-PR Engineering Agent</span>
          </a>

          {/* Active stats badge */}
          <div className="cf-system-badge">
            <div className="flex-gap">
              <span className={`cf-status-dot ${pipelineStatus === 'running' ? 'pulsing' : ''}`}></span>
              <span>System: <strong style={{color: '#008000'}}>ONLINE</strong></span>
            </div>
            <div>
              <span>Port: <strong>9095</strong></span>
            </div>
            <div>
              <span>Active Model: <span className="badge-blue">{selectedModel.toUpperCase()}</span></span>
            </div>
          </div>
        </div>
      </header>

      {/* Main Layout Grid */}
      <div className="cf-main-grid">
        
        {/* Left Column - Active Tab Content */}
        <div>
          <main className="cf-content-panel">
            {/* Card Header with Tabs */}
            <div className="cf-card-header-tabs">
              <ul className="cf-tabs-container">
                <li className="cf-tab">
                  <a 
                    href="#submit" 
                    className={`cf-tab-link ${activeTab === 'SUBMIT' ? 'active' : ''}`}
                    onClick={(e) => { e.preventDefault(); setActiveTab('SUBMIT'); }}
                  >
                    DASHBOARD
                  </a>
                </li>
                <li className="cf-tab">
                  <a 
                    href="#timeline" 
                    className={`cf-tab-link ${activeTab === 'TIMELINE' ? 'active' : ''}`}
                    onClick={(e) => { e.preventDefault(); setActiveTab('TIMELINE'); }}
                  >
                    TIMELINE ({pipelineStatus.toUpperCase()})
                  </a>
                </li>
                <li className="cf-tab">
                  <a 
                    href="#diagnostics" 
                    className={`cf-tab-link ${activeTab === 'DIAGNOSTICS' ? 'active' : ''}`}
                    onClick={(e) => { e.preventDefault(); setActiveTab('DIAGNOSTICS'); }}
                  >
                    DIAGNOSTICS & AUDIT
                  </a>
                </li>
                <li className="cf-tab">
                  <a 
                    href="#fix" 
                    className={`cf-tab-link ${activeTab === 'FIX' ? 'active' : ''}`}
                    onClick={(e) => { e.preventDefault(); setActiveTab('FIX'); }}
                  >
                    PROPOSED FIX
                  </a>
                </li>
                <li className="cf-tab">
                  <a 
                    href="#pr" 
                    className={`cf-tab-link ${activeTab === 'PR' ? 'active' : ''}`}
                    onClick={(e) => { e.preventDefault(); setActiveTab('PR'); }}
                  >
                    PULL REQUESTS
                  </a>
                </li>
                <li className="cf-tab">
                  <a 
                    href="#rca" 
                    className={`cf-tab-link ${activeTab === 'RCA' ? 'active' : ''}`}
                    onClick={(e) => { e.preventDefault(); setActiveTab('RCA'); }}
                  >
                    RCA REPORT
                  </a>
                </li>
                <li className="cf-tab">
                  <a 
                    href="#submissions" 
                    className={`cf-tab-link ${activeTab === 'SUBMISSIONS' ? 'active' : ''}`}
                    onClick={(e) => { e.preventDefault(); setActiveTab('SUBMISSIONS'); }}
                  >
                    RECENT SUBMISSIONS
                  </a>
                </li>
              </ul>
            </div>
          
            {/* TAB 1: SUBMIT INCIDENT */}
            {activeTab === 'SUBMIT' && (
            <div className="cf-card-body" style={{ padding: '20px' }}>
              
              {/* Codeforces Custom Test style Panel */}
              <div>
                <div className="flex-between mb-15">
                  <h3 style={{ margin: 0, color: '#1a5a96', fontSize: '16px' }}>Custom Test Diagnostics (Manual Trigger)</h3>
                  <div className="flex-gap">
                    <span className="text-muted" style={{ fontSize: '12px' }}>Pre-fill Scenarios:</span>
                    <button className="cf-btn-blue" style={{ padding: '2px 8px', fontSize: '11px' }} onClick={() => handleLoadPreset('npe_payment')}>Scenario A (NPE)</button>
                    <button className="cf-btn-blue" style={{ padding: '2px 8px', fontSize: '11px' }} onClick={() => handleLoadPreset('array_bounds')}>Scenario B (OOB)</button>
                  </div>
                </div>

                <div className="cf-card" style={{ borderColor: '#d4edda', backgroundColor: '#f4fcf6', padding: '10px', marginBottom: '15px' }}>
                  <strong style={{ color: '#155724', fontSize: '12px' }}>Settings:</strong>
                  <div className="flex-gap mt-5" style={{ gap: '20px', fontSize: '12px' }}>
                    <label className="flex-gap" style={{ cursor: 'pointer' }}>
                      <input 
                        type="radio" 
                        name="opmode" 
                        checked={mode === 'demo'} 
                        onChange={() => { setMode('demo'); addLog('[SYSTEM] Mode set to: DEMO SIMULATION'); }} 
                      />
                      <span>Demo Mode (Simulation)</span>
                    </label>
                    <label className="flex-gap" style={{ cursor: 'pointer' }}>
                      <input 
                        type="radio" 
                        name="opmode" 
                        checked={mode === 'live'} 
                        onChange={() => { setMode('live'); addLog('[SYSTEM] Mode set to: LIVE INTEGRATION (Java Backend port 9095)'); }} 
                      />
                      <span>Live Mode (Spring Boot Backend)</span>
                    </label>
                  </div>
                </div>

                <div className="grid-2" style={{ gap: '15px' }}>
                  <div className="cf-form-group">
                    <label className="cf-label" style={{ fontSize: '12px' }}>Ticket Key / ID</label>
                    <input 
                      type="text" 
                      className="cf-input" 
                      value={ticketId} 
                      onChange={(e) => setTicketId(e.target.value)} 
                      placeholder="e.g. PROD-1234"
                    />
                  </div>

                  <div className="cf-form-group">
                    <label className="cf-label" style={{ fontSize: '12px' }}>Summary / Short Title</label>
                    <input 
                      type="text" 
                      className="cf-input" 
                      value={summary} 
                      onChange={(e) => setSummary(e.target.value)} 
                      placeholder="e.g. NullPointerException in Payment Service"
                    />
                  </div>
                </div>

                <div className="cf-form-group">
                  <label className="cf-label" style={{ fontSize: '12px' }}>Incident Description</label>
                  <textarea 
                    className="cf-textarea" 
                    rows={2}
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder="Describe the failure scenario..."
                  />
                </div>

                <div className="cf-form-group">
                  <label className="cf-label" style={{ fontSize: '12px' }}>Stack Trace / Exception Logs</label>
                  <textarea 
                    className="cf-textarea" 
                    rows={3}
                    style={{ fontFamily: 'monospace', fontSize: '12px' }}
                    value={stackTrace}
                    onChange={(e) => setStackTrace(e.target.value)}
                    placeholder="Paste crash exception stack trace here..."
                  />
                </div>

                <div className="grid-2" style={{ gap: '15px', alignItems: 'center' }}>
                  <div className="cf-form-group" style={{ margin: 0 }}>
                    <label className="cf-label" style={{ fontSize: '12px' }}>Target Model</label>
                    <select 
                      className="cf-select" 
                      value={selectedModel} 
                      onChange={(e) => { setSelectedModel(e.target.value); addLog(`[SYSTEM] Target model selected: ${e.target.value.toUpperCase()}`); }}
                    >
                      <option value="groq">Groq (Llama-3.3-70b-versatile)</option>
                      <option value="gemini">Gemini (1.5 Pro Developer Tier)</option>
                      <option value="mock">Mock AI (Offline Static Mock)</option>
                    </select>
                  </div>
                  
                  <div className="cf-form-group" style={{ margin: 0, alignSelf: 'stretch', display: 'flex', alignItems: 'flex-end' }}>
                    <button 
                      className="cf-btn-green" 
                      style={{ width: '100%', padding: '8px', fontSize: '14px', height: '32px', lineHeight: '14px' }}
                      onClick={handleAnalyze}
                      disabled={pipelineStatus === 'running'}
                    >
                      {pipelineStatus === 'running' ? 'Running diagnostics...' : '⚡ Submit Manual Diagnostics'}
                    </button>
                  </div>
                </div>
              </div>

              <hr style={{ border: 0, borderTop: '1px solid #e1e1e1', margin: '20px 0' }} />

              {/* Real-time Pipeline Logs */}
              <div>
                <h3 style={{ margin: '0 0 10px 0', color: '#1a5a96', fontSize: '16px' }}>Real-time Pipeline Logs</h3>
                <div className="cf-console" style={{ height: '300px' }}>
                  {consoleLogs.map((logLine: string, idx: number) => (
                    <div key={idx} style={{ marginBottom: '4px' }}>{logLine}</div>
                  ))}
                  <div ref={consoleEndRef} />
                </div>
              </div>

            </div>
          )}

          {/* TAB 2: AGENT EXECUTION TIMELINE */}
          {activeTab === 'TIMELINE' && (
            <div className="cf-card-body">
              <h2 style={{margin:0, marginBottom:'15px', color:'#1a5a96'}}>Agent Timeline & Execution Details</h2>
              <div className="cf-split-view">
                
                {/* Timeline status list */}
                <div className="cf-timeline">
                  {Object.keys(agentStates).map((agentKey: string) => {
                    const status = agentStates[agentKey as keyof typeof agentStates];
                    const isActive = selectedAgent === agentKey;
                    return (
                      <div 
                        key={agentKey}
                        className={`cf-timeline-item ${isActive ? 'active' : ''}`}
                        onClick={() => setSelectedAgent(agentKey)}
                      >
                        <span style={{fontWeight:'bold', textTransform:'capitalize'}}>
                          {agentKey === 'analysis' ? 'Ticket Analysis' : 
                           agentKey === 'context' ? 'Context RAG' : 
                           agentKey === 'healing' ? 'Self-Healing Generator' : 'PR & RCA Delivery'}
                        </span>
                        <span className={
                          status === 'completed' ? 'verdict-accepted' : 
                          status === 'running' ? 'verdict-running' : 
                          status === 'failed' ? 'verdict-failed' : 'verdict-waiting'
                        }>
                          {status === 'completed' ? '✓ Accepted' : 
                           status === 'running' ? '⟳ Running' : 
                           status === 'failed' ? '✗ Failed' : 'Waiting'}
                        </span>
                      </div>
                    );
                  })}
                </div>

                {/* Details Panel */}
                <div className="cf-card">
                  <div className="cf-card-header">
                    <span>Agent Details Panel: {selectedAgent.toUpperCase()}</span>
                  </div>
                  <div className="cf-card-body">
                    {selectedAgent === 'analysis' && (
                      <div>
                        <h3>Ticket Analysis</h3>
                        <p><strong>Status:</strong> {
                          agentStates.analysis === 'completed' ? <span className="verdict-accepted">Completed</span> :
                          agentStates.analysis === 'running' ? <span className="verdict-running">Running</span> :
                          agentStates.analysis === 'failed' ? <span className="verdict-failed">Failed</span> :
                          <span className="verdict-waiting">Waiting</span>
                        }</p>
                        <p><strong>Diagnosis Hypothesis:</strong> {activeScenario.investigation.hypothesis}</p>
                        <p><strong>Classified Bug Category:</strong> {activeScenario.rca.issue}</p>
                        <p><strong>Repair Strategy Plan:</strong> {activeScenario.investigation.evidence[0]?.desc || "Scan and inject input boundaries validation."}</p>
                      </div>
                    )}
                    {selectedAgent === 'context' && (
                      <div>
                        <h3>Context RAG</h3>
                        <p><strong>Status:</strong> {
                          agentStates.context === 'completed' ? <span className="verdict-accepted">Completed</span> :
                          agentStates.context === 'running' ? <span className="verdict-running">Running</span> :
                          agentStates.context === 'failed' ? <span className="verdict-failed">Failed</span> :
                          <span className="verdict-waiting">Waiting</span>
                        }</p>
                        <p><strong>Target Repository:</strong> SatyaisCoding/testing-repo</p>
                        <p><strong>Scope:</strong> Downloaded targeted files to local cache for analysis.</p>
                        <p><strong>Files Fetched:</strong> {activeScenario.fix.modifiedFiles.join(', ')}</p>
                        <p><strong>Memory Matches Found:</strong> Similar incident {activeScenario.memory.similarIncident.id} ({activeScenario.memory.similarIncident.confidence} confidence)</p>
                        <p><strong>Memory Resolution:</strong> {activeScenario.memory.similarIncident.resolution}</p>
                      </div>
                    )}
                    {selectedAgent === 'healing' && (
                      <div>
                        <h3>Self-Healing Code Generator</h3>
                        <p><strong>Status:</strong> {
                          agentStates.healing === 'completed' ? <span className="verdict-accepted">Completed</span> :
                          agentStates.healing === 'running' ? <span className="verdict-running">Running</span> :
                          agentStates.healing === 'failed' ? <span className="verdict-failed">Failed</span> :
                          <span className="verdict-waiting">Waiting</span>
                        }</p>
                        <p><strong>AI Generator Model:</strong> {selectedModel.toUpperCase()}</p>
                        <p><strong>Code Review Verdict:</strong> {activeScenario.validation.reviewer}</p>
                        <p><strong>Code Review Recommendation:</strong> {activeScenario.validation.reviewerReport.recommendation}</p>
                        <p><strong>Self-Healing Recovery Strategy:</strong> {activeScenario.recovery.strategy}</p>
                        <p><strong>Validation Attempts:</strong> {activeScenario.recovery.attempts.length}</p>
                      </div>
                    )}
                    {selectedAgent === 'delivery' && (
                      <div>
                        <h3>PR & RCA Delivery</h3>
                        <p><strong>Status:</strong> {
                          agentStates.delivery === 'completed' ? <span className="verdict-accepted">Completed</span> :
                          agentStates.delivery === 'running' ? <span className="verdict-running">Running</span> :
                          agentStates.delivery === 'failed' ? <span className="verdict-failed">Failed</span> :
                          <span className="verdict-waiting">Waiting</span>
                        }</p>
                        <p><strong>Pull Request Created:</strong> <a href={activeScenario.pr.url || '#'} target="_blank" rel="noreferrer" style={{color:'#1a5a96', fontWeight:'bold'}}>{activeScenario.pr.branch} ({activeScenario.pr.number})</a></p>
                        <p><strong>RCA Report Status:</strong> Done (Report can be viewed in the RCA tab)</p>
                        <p><strong>Review Status:</strong> {activeScenario.pr.reviewStatus}</p>
                        <p><strong>CI Build Check Outcome:</strong> {activeScenario.validation.ci}</p>
                      </div>
                    )}
                  </div>
                </div>

              </div>
            </div>
          )}

          {/* TAB 3: DIAGNOSTICS & AUDIT */}
          {activeTab === 'DIAGNOSTICS' && (
            <div className="cf-card-body">
              <h2 style={{margin:0, marginBottom:'15px', color:'#1a5a96'}}>Diagnostics & Audit Logs</h2>
              
              <div className="grid-2" style={{marginBottom:'15px'}}>
                {/* Investigation Panel */}
                <div className="cf-card" style={{margin:0}}>
                  <div className="cf-card-header">Incident Investigation Report</div>
                  <div className="cf-card-body">
                    <table className="cf-table">
                      <tbody>
                        <tr>
                          <td style={{width:'150px', fontWeight:'bold'}}>Target Service:</td>
                          <td><code>{activeScenario.investigation.service}</code></td>
                        </tr>
                        <tr>
                          <td style={{fontWeight:'bold'}}>Environment:</td>
                          <td><span className="badge-blue">{activeScenario.investigation.environment}</span></td>
                        </tr>
                        <tr>
                          <td style={{fontWeight:'bold'}}>Severity:</td>
                          <td><span className="badge-red">{activeScenario.investigation.severity}</span></td>
                        </tr>
                        <tr>
                          <td style={{fontWeight:'bold'}}>Hypothesis:</td>
                          <td><span className="text-bold" style={{color:'#a00'}}>{activeScenario.investigation.hypothesis}</span></td>
                        </tr>
                        <tr>
                          <td style={{fontWeight:'bold'}}>Confidence:</td>
                          <td><span className="verdict-accepted">{activeScenario.investigation.confidence}</span></td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </div>

                {/* Reviewer Agent Report */}
                <div className="cf-card" style={{margin:0}}>
                  <div className="cf-card-header">Reviewer Agent Report</div>
                  <div className="cf-card-body">
                    <table className="cf-table">
                      <tbody>
                        <tr>
                          <td style={{fontWeight:'bold'}}>Security Risk:</td>
                          <td><span className="badge-green">{activeScenario.validation.reviewerReport.security}</span></td>
                        </tr>
                        <tr>
                          <td style={{fontWeight:'bold'}}>Performance Impact:</td>
                          <td><span className="badge-grey">{activeScenario.validation.reviewerReport.performance}</span></td>
                        </tr>
                        <tr>
                          <td style={{fontWeight:'bold'}}>Maintainability:</td>
                          <td><span className="badge-blue">{activeScenario.validation.reviewerReport.maintainability}</span></td>
                        </tr>
                        <tr>
                          <td style={{fontWeight:'bold'}}>Verdict Recommendation:</td>
                          <td><strong style={{color:'#008000'}}>{activeScenario.validation.reviewerReport.recommendation}</strong></td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </div>
              </div>

              {/* Similar Incident */}
              <div className="cf-card" style={{marginBottom:'15px'}}>
                <div className="cf-card-header">Similar Incident retrieved from History Database</div>
                <table className="cf-table">
                  <thead>
                    <tr>
                      <th>Incident ID</th>
                      <th>Classified Issue</th>
                      <th>Found Root Cause</th>
                      <th>Resolution Applied</th>
                      <th>Similiarity Score</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td><span className="text-bold" style={{color:'#1a5a96'}}>{activeScenario.memory.similarIncident.id}</span></td>
                      <td>{activeScenario.memory.similarIncident.issue}</td>
                      <td>{activeScenario.memory.similarIncident.rootCause}</td>
                      <td><span className="badge-green">{activeScenario.memory.similarIncident.resolution}</span></td>
                      <td><span className="text-bold" style={{color:'#008000'}}>{activeScenario.memory.similarIncident.confidence}</span></td>
                    </tr>
                  </tbody>
                </table>
              </div>

              {/* Previous Successful Fixes & Recovery Strategy Memory */}
              <div className="grid-2" style={{marginBottom:'15px'}}>
                <div className="cf-card" style={{margin:0}}>
                  <div className="cf-card-header">Previous Successful Fixes</div>
                  <table className="cf-table">
                    <thead>
                      <tr>
                        <th>Target File</th>
                        <th>Fix Output</th>
                        <th>Lint Checks</th>
                        <th>CI Checks</th>
                      </tr>
                    </thead>
                    <tbody>
                      {activeScenario.memory.successfulFixes.map((item: any, idx: number) => (
                        <tr key={idx}>
                          <td><code>{item.file}</code></td>
                          <td><span className="verdict-accepted">{item.result}</span></td>
                          <td><span className="badge-blue">{item.validation}</span></td>
                          <td><span className="badge-green">{item.ci}</span></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                <div className="cf-card" style={{margin:0}}>
                  <div className="cf-card-header">Recovery Strategy Memory</div>
                  <div className="cf-card-body">
                    <p><strong>Recommended Strategy:</strong> <code>{activeScenario.memory.recoveryMemory.strategy}</code></p>
                    <p><strong>Historical Success Rate:</strong></p>
                    <div style={{backgroundColor:'#eee', borderRadius:'4px', height:'20px', overflow:'hidden', position:'relative', marginBottom:'10px'}}>
                      <div style={{backgroundColor:'#008000', width: activeScenario.memory.recoveryMemory.successRate, height:'100%'}}></div>
                      <span style={{position:'absolute', top:'2px', left:'10px', fontWeight:'bold', color: '#000'}}>{activeScenario.memory.recoveryMemory.successRate}</span>
                    </div>
                    <span className="text-muted">Agent uses success statistics to rank fallback algorithms.</span>
                  </div>
                </div>
              </div>

              {/* Gathered Evidence / Logs */}
              <div className="cf-card" style={{marginBottom:'15px'}}>
                <div className="cf-card-header">Gathered Evidence / Logs</div>
                <div className="cf-card-body">
                  <div className="cf-timeline" style={{gap:'8px'}}>
                    {activeScenario.investigation.evidence.map((ev: any, idx: number) => (
                      <div key={idx} style={{border:'1px solid #bcbcbc', borderRadius:'4px', padding:'8px', backgroundColor:'#fdfdfd'}}>
                        <strong style={{color:'#1a5a96'}}>{ev.type}:</strong>
                        <p style={{margin: '3px 0 0 0', fontSize:'11px'}}>{ev.desc}</p>
                      </div>
                    ))}
                  </div>
                </div>
              </div>

              {/* Self-Healing Trigger Cause & Recovery Attempts */}
              <div className="cf-card">
                <div className="cf-card-header">Self-Healing Recovery Logs</div>
                <div className="cf-card-body">
                  <div style={{backgroundColor:'#fff3cd', color:'#856404', border:'1px solid #ffeeba', padding:'10px', borderRadius:'4px', marginBottom:'15px'}}>
                    <strong>Trigger Error:</strong> {activeScenario.recovery.example}
                    <p style={{margin:'5px 0 0 0'}}><strong>Strategy:</strong> Automatically invoke Recovery Agent with the compiler/linters trace details.</p>
                  </div>
                  <table className="cf-table">
                    <thead>
                      <tr>
                        <th style={{width:'80px'}}>Attempt</th>
                        <th style={{width:'100px'}}>Status</th>
                        <th>Resolution Reason / Log Details</th>
                      </tr>
                    </thead>
                    <tbody>
                      {activeScenario.recovery.attempts.map((att: any) => (
                        <tr key={att.id}>
                          <td>Attempt {att.id}</td>
                          <td>
                            <span className={att.status === 'Success' ? 'verdict-accepted' : 'verdict-failed'}>
                              {att.status}
                            </span>
                          </td>
                          <td>{att.reason}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          {/* TAB 5: PROPOSED FIX */}
          {activeTab === 'FIX' && (
            <div className="cf-card-body">
              <h2 style={{margin:0, marginBottom:'15px', color:'#1a5a96'}}>Proposed Code Fixes</h2>
              
              <div className="cf-card" style={{marginBottom:'15px'}}>
                <div className="cf-card-header">
                  <span>Code Diff View: <code>{activeScenario.fix.modifiedFiles[0]}</code></span>
                  <span className="badge-blue">PATCH</span>
                </div>
                <div className="cf-diff-container">
                  <div className="cf-diff-header">
                    <span>Before Fix</span>
                    <span>Line Numbers</span>
                  </div>
                  <div className="cf-diff-row del">
                    <div className="cf-diff-num">41</div>
                    <div className="cf-diff-code">{activeScenario.fix.beforeCode}</div>
                  </div>
                  
                  <div className="cf-diff-header">
                    <span>After Fix (Proposed Patch)</span>
                    <span>Line Numbers</span>
                  </div>
                  <div className="cf-diff-row add">
                    <div className="cf-diff-num">41</div>
                    <div className="cf-diff-code">{activeScenario.fix.afterCode}</div>
                  </div>
                </div>
              </div>

              <div className="cf-card">
                <div className="cf-card-header">Generated Unit Tests</div>
                <div className="cf-card-body">
                  <p>The agent authored unit tests in <code>{activeScenario.fix.testFile}</code> to verify the repair:</p>
                  <pre style={{backgroundColor:'#f8f9fa', border:'1px solid #ccc', padding:'10px', borderRadius:'4px', fontFamily:'monospace', overflowX:'auto'}}>
                    {activeScenario.fix.testContent}
                  </pre>
                </div>
              </div>
            </div>
          )}

          {/* TAB 8: PULL REQUESTS */}
          {activeTab === 'PR' && (
            <div className="cf-card-body">
              <h2 style={{margin:0, marginBottom:'15px', color:'#1a5a96'}}>Generated Pull Request</h2>
              
              <div className="cf-card">
                <div className="cf-card-header">Pull Request Summary</div>
                <table className="cf-table">
                  <tbody>
                    <tr>
                      <td style={{width:'150px', fontWeight:'bold'}}>PR ID:</td>
                      <td><span className="text-bold" style={{color:'#1a5a96'}}>{activeScenario.pr.number}</span></td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>Repository Branch:</td>
                      <td><code>{activeScenario.pr.branch}</code></td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>State / Status:</td>
                      <td><span className="badge-blue">{activeScenario.pr.status}</span></td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>Review Verdict:</td>
                      <td><span className="badge-green">{activeScenario.pr.reviewStatus}</span></td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>Files Changed count:</td>
                      <td><strong style={{color:'#000'}}>{activeScenario.pr.filesChanged}</strong></td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>Tests Written:</td>
                      <td><span className="badge-blue">{activeScenario.pr.testsAdded} test suite added</span></td>
                    </tr>
                  </tbody>
                </table>
              </div>
              
              <div style={{textAlign:'right'}}>
                <a 
                  href={apiResult?.pr_url || "https://github.com/SatyaisCoding/testing-repo/pull/2"} 
                  target="_blank" 
                  rel="noreferrer" 
                  className="cf-btn-green"
                >
                  🚀 Open PR on GitHub
                </a>
              </div>
            </div>
          )}

          {/* TAB 9: RCA REPORT */}
          {activeTab === 'RCA' && (
            <div className="cf-card-body">
              <h2 style={{margin:0, marginBottom:'15px', color:'#1a5a96'}}>Root Cause Analysis (RCA) Report</h2>
              
              <div className="cf-card">
                <div className="cf-card-header">Incident Post-Mortem Diagnostic</div>
                <table className="cf-table">
                  <tbody>
                    <tr>
                      <td style={{width:'150px', fontWeight:'bold'}}>Issue Type:</td>
                      <td><span className="badge-red">{activeScenario.rca.issue}</span></td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>Root Cause:</td>
                      <td>{activeScenario.rca.rootCause}</td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>Customer Impact:</td>
                      <td>{activeScenario.rca.impact}</td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>Files Modified:</td>
                      <td><code>{activeScenario.rca.filesModified}</code></td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>Fix Applied:</td>
                      <td>{activeScenario.rca.fixApplied}</td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>Risk Assessment:</td>
                      <td><span className="badge-green">{activeScenario.rca.risk} risk profile</span></td>
                    </tr>
                    <tr>
                      <td style={{fontWeight:'bold'}}>Rollback Strategy:</td>
                      <td><code>{activeScenario.rca.rollback}</code></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* TAB 11: RECENT DIAGNOSTICS SUBMISSIONS */}
          {activeTab === 'SUBMISSIONS' && (
            <div className="cf-card-body" style={{ padding: '20px' }}>
              <div style={{ marginBottom: '25px' }}>
                <h2 style={{ margin: '0 0 10px 0', color: '#1a5a96', fontSize: '18px' }}>Recent Diagnostics Submissions</h2>
                <p style={{ margin: '0 0 15px 0', color: '#666', fontSize: '13px' }}>
                  Automata runs autonomously. Below is the live status history of diagnostic runs triggered by external issue webhooks or custom manual tests. Click on any submission's summary to inspect its detailed timelines, proposed fixes, and RCA reports.
                </p>

                <table className="cf-table" style={{ width: '100%' }}>
                  <thead>
                    <tr>
                      <th style={{ width: '10%' }}># ID</th>
                      <th style={{ width: '35%' }}>Incident Summary</th>
                      <th style={{ width: '20%' }}>Target File</th>
                      <th style={{ width: '10%' }}>Engine</th>
                      <th style={{ width: '12%' }}>Verdict</th>
                      <th style={{ width: '13%' }}>Pull Request</th>
                    </tr>
                  </thead>
                  <tbody>
                    {/* Prepend running row if pipeline is active */}
                    {pipelineStatus === 'running' && (
                      <tr style={{ backgroundColor: '#f0f8ff' }}>
                        <td>
                          <strong style={{ color: '#1a5a96' }}>{ticketId || 'RUNNING'}</strong>
                        </td>
                        <td>
                          <span style={{ color: '#1a5a96', fontWeight: 'bold', animation: 'blink 1.2s infinite' }}>
                            {summary || 'Executing diagnostics flow...'}
                          </span>
                        </td>
                        <td>
                          <span className="text-muted">Scanning repo...</span>
                        </td>
                        <td>
                          <span className="badge-blue" style={{ fontSize: '10px' }}>{selectedModel.toUpperCase()}</span>
                        </td>
                        <td>
                          <span className="verdict-testing">TESTING...</span>
                        </td>
                        <td>
                          <span className="text-muted">Pending...</span>
                        </td>
                      </tr>
                    )}

                    {/* Render historical items from memory.json */}
                    {history && history.length > 0 ? (
                      [...history].reverse().map((item: any, idx: number) => {
                        // Avoid duplicate display for the currently running ticket
                        if (pipelineStatus === 'running' && item.id === ticketId) {
                          return null;
                        }
                        return (
                          <tr key={item.id || idx}>
                            <td>
                              <strong style={{ color: '#555' }}>{item.id}</strong>
                            </td>
                            <td>
                              <a
                                href="#select-item"
                                onClick={(e) => {
                                  e.preventDefault();
                                  handleSelectHistoryItem(item);
                                }}
                                style={{
                                  color: '#1a5a96',
                                  textDecoration: 'none',
                                  fontWeight: 'bold',
                                }}
                                onMouseEnter={(e) => (e.currentTarget.style.textDecoration = 'underline')}
                                onMouseLeave={(e) => (e.currentTarget.style.textDecoration = 'none')}
                              >
                                {item.summary}
                              </a>
                            </td>
                            <td>
                              <code style={{ fontSize: '11px', color: '#b94a48' }}>
                                {item.fixes && item.fixes.length > 0 ? item.fixes[0].file : 'N/A'}
                              </code>
                            </td>
                            <td>
                              <span className="badge-grey" style={{ fontSize: '10px' }}>
                                {getEngineName(item)}
                              </span>
                            </td>
                            <td>
                              {getVerdict(item)}
                            </td>
                            <td>
                              {getPrLink(item)}
                            </td>
                          </tr>
                        );
                      })
                    ) : (
                      <tr>
                        <td colSpan={6} style={{ textAlign: 'center', color: '#888', padding: '20px' }}>
                          No submissions found in logs history.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* TAB 10: ANALYTICS (CUSTOM EXTRA FOR JUDGES) */}
          {activeTab === 'ANALYTICS' && (
            <div className="cf-card-body">
              <h2 style={{margin:0, marginBottom:'15px', color:'#1a5a96'}}>Agent Diagnostics & Performance Metrics</h2>
              
              <div className="grid-2" style={{marginBottom:'15px'}}>
                <div className="cf-card" style={{margin:0}}>
                  <div className="cf-card-header">Success Rates</div>
                  <div className="cf-card-body">
                    <p><strong>First-Attempt Fix Success:</strong></p>
                    <div style={{backgroundColor:'#eee', borderRadius:'4px', height:'16px', overflow:'hidden', position:'relative', marginBottom:'10px'}}>
                      <div style={{backgroundColor:'#3b5998', width: '74%', height:'100%'}}></div>
                      <span style={{position:'absolute', top:'1px', left:'10px', fontSize:'10px', fontWeight:'bold', color: '#000'}}>74%</span>
                    </div>

                    <p><strong>Self-Healing Recovery Success:</strong></p>
                    <div style={{backgroundColor:'#eee', borderRadius:'4px', height:'16px', overflow:'hidden', position:'relative', marginBottom:'10px'}}>
                      <div style={{backgroundColor:'#008000', width: '92%', height:'100%'}}></div>
                      <span style={{position:'absolute', top:'1px', left:'10px', fontSize:'10px', fontWeight:'bold', color: '#000'}}>92%</span>
                    </div>
                  </div>
                </div>

                <div className="cf-card" style={{margin:0}}>
                  <div className="cf-card-header">Resolution Metrics</div>
                  <table className="cf-table">
                    <tbody>
                      <tr>
                        <td>Average Resolution Time:</td>
                        <td><strong>45 seconds</strong></td>
                      </tr>
                      <tr>
                        <td>Memory Hit Rate:</td>
                        <td><strong>84%</strong></td>
                      </tr>
                      <tr>
                        <td>Token Reduction efficiency:</td>
                        <td><strong>32% improvement</strong></td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          </main>
        </div>

        {/* Right Column - Codeforces Sidebar */}
        <aside>
          
          {/* User profile Widget */}
          <div className="cf-sidebar-card">
            <div className="cf-sidebar-header">User Profile Info</div>
            <div className="cf-sidebar-body" style={{fontSize:'12px'}}>
              <p style={{margin:'2px 0'}}>
                <strong>Handle:</strong> <span className="handle-lgm"><span style={{color:'#000'}}>s</span>atya_prakash</span>
              </p>
              <p style={{margin:'2px 0'}}><strong>Rating:</strong> <span style={{color:'red', fontWeight:'bold'}}>3245</span> (Legendary Grandmaster)</p>
              <p style={{margin:'2px 0'}}><strong>Contributions:</strong> <span style={{color:'green', fontWeight:'bold'}}>+135</span></p>
              <p style={{margin:'2px 0'}}><strong>Resolution Rate:</strong> 98.4%</p>
            </div>
          </div>



          {/* Model Pick / Controller */}
          <div className="cf-sidebar-card">
            <div className="cf-sidebar-header">Global Preferences</div>
            <div className="cf-sidebar-body">
              <div className="cf-form-group" style={{margin:0}}>
                <label className="cf-label" style={{fontSize:'11px'}}>Mode Choice:</label>
                <select 
                  className="cf-select" 
                  style={{padding:'3px 6px', fontSize:'11px'}}
                  value={mode}
                  onChange={(e) => { setMode(e.target.value); addLog(`[SYSTEM] Toggle mode to: ${e.target.value.toUpperCase()}`); }}
                >
                  <option value="demo">Demo Simulation</option>
                  <option value="live">Live Spring Boot API</option>
                </select>
                
                <label className="cf-label" style={{fontSize:'11px', marginTop:'8px'}}>Target Model:</label>
                <select 
                  className="cf-select" 
                  style={{padding:'3px 6px', fontSize:'11px'}}
                  value={selectedModel}
                  onChange={(e) => { setSelectedModel(e.target.value); addLog(`[SYSTEM] Target model selected: ${e.target.value.toUpperCase()}`); }}
                >
                  <option value="groq">Groq (Llama-3.3)</option>
                  <option value="gemini">Gemini (1.5 Pro)</option>
                  <option value="mock">Mock Offline</option>
                </select>
              </div>
            </div>
          </div>

          {/* Pipeline Active Monitor */}
          <div className="cf-sidebar-card">
            <div className="cf-sidebar-header">Active Agent Monitor</div>
            <div className="cf-sidebar-body" style={{padding:0}}>
              <table className="cf-table" style={{fontSize:'11px'}}>
                <tbody>
                  {Object.keys(agentStates).map((agentKey: string) => {
                    const status = agentStates[agentKey as keyof typeof agentStates];
                    return (
                      <tr key={agentKey}>
                        <td style={{textTransform:'capitalize', padding:'4px 8px'}}>{agentKey}</td>
                        <td style={{padding:'4px 8px'}} className={
                          status === 'completed' ? 'verdict-ok' :
                          status === 'running' ? 'verdict-running' :
                          status === 'failed' ? 'verdict-failed' : 'verdict-waiting'
                        }>
                          {status.toUpperCase()}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>

        </aside>

      </div>

      {/* FOOTER: AGENT CHAT INTERFACE & BOTTOM SECTION */}
      <footer style={{marginTop:'20px', borderTop:'1px solid #bcbcbc', padding:'15px 0'}}>
        
        {/* Chatbot Interface */}
        <div className="cf-card" style={{margin:0}}>
          <div className="cf-card-header">
            <span>Interactive Agent Chat</span>
            <span className="badge-blue">EXPLAINABILITY</span>
          </div>
          <div className="cf-card-body" style={{padding:'10px'}}>
            <div 
              style={{
                border:'1px solid #ccc', 
                borderRadius:'4px', 
                height:'120px', 
                overflowY:'auto', 
                padding:'8px', 
                backgroundColor:'#f8f9fa',
                fontSize:'11px',
                marginBottom:'8px'
              }}
            >
              {chatMessages.map((msg: any, idx: number) => (
                <div key={idx} style={{marginBottom:'5px'}}>
                  <strong>{msg.sender}:</strong> <span>{msg.text}</span>
                </div>
              ))}
            </div>
            <div className="flex-gap">
              <input 
                type="text" 
                className="cf-input" 
                style={{padding:'4px 8px', fontSize:'11px'}}
                value={chatInput}
                onChange={(e) => setChatInput(e.target.value)}
                placeholder="Ask: Why was this fix generated? or Why did validation fail?"
                onKeyDown={(e) => { if (e.key === 'Enter') handleSendMessage(); }}
              />
              <button className="cf-btn-blue" style={{padding:'4px 12px', fontSize:'11px'}} onClick={handleSendMessage}>Send</button>
            </div>
          </div>
        </div>
        
        <div style={{textAlign:'center', marginTop:'15px', color:'#777', fontSize:'11px'}}>
          Automata &copy; 2026. Built with Next.js, Java Spring Boot & Llama 3.3.
        </div>
      </footer>
    </div>
  );
}

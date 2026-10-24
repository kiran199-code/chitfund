import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth';
import Layout from './components/Layout';
import { Loading } from './components/ui';
import Batch from './pages/Batch';
import Calendar from './pages/Calendar';
import ChitCycle from './pages/ChitCycle';
import ChitDues from './pages/ChitDues';
import ChitGroupDetail from './pages/ChitGroupDetail';
import ChitGroupNew from './pages/ChitGroupNew';
import ChitGroups from './pages/ChitGroups';
import ChitTicket from './pages/ChitTicket';
import EndOfDay from './pages/EndOfDay';
import Home from './pages/Home';
import Login from './pages/Login';
import Maintenance from './pages/Maintenance';

function Gate() {
  const { me, checking } = useAuth();
  if (checking) return <Loading what="Starting" />;
  if (!me) return <Login />;
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="chit/groups" element={<ChitGroups />} />
        <Route path="chit/groups/new" element={<ChitGroupNew />} />
        <Route path="chit/groups/:code" element={<ChitGroupDetail />} />
        <Route path="chit/groups/:code/tickets/:no" element={<ChitTicket />} />
        <Route path="chit/groups/:code/cycles/:no" element={<ChitCycle />} />
        <Route path="chit/dues" element={<ChitDues />} />
        <Route path="calendar" element={<Calendar />} />
        <Route path="batch" element={<Batch />} />
        <Route path="eod" element={<EndOfDay />} />
        <Route path="maintenance" element={<Maintenance />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <HashRouter>
      <AuthProvider>
        <Gate />
      </AuthProvider>
    </HashRouter>
  );
}
